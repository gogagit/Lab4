package ru.semenyutin.lab4;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

final class Export {
    enum ChartFormat { PNG, SVG, PDF, HTML }
    enum TableFormat { CSV, XLSX, JSON, MD }
    private static final List<String> HEAD=List.of("Столбец","N","Пропущено","Мин.","Макс.","Среднее","Ст. отклонение","Q1","Медиана","Q3","IQR","Ср. абс. отклонение","Выбросы IQR","Выбросы Z");
    private Export(){}
    static void chart(Path p,Plot plot,ChartFormat kind)throws IOException {
        switch(kind){
            case PNG -> Files.write(p,plot.png());
            case SVG -> Files.writeString(p,plot.svg(),StandardCharsets.UTF_8);
            case HTML -> Files.writeString(p,"<!doctype html><html lang=\"ru\"><meta charset=\"utf-8\"><title>Статистика</title><body>"+plot.svg()+"</body></html>",StandardCharsets.UTF_8);
            case PDF -> {
                try(var doc=new PDDocument()){
                    var page=new PDPage(new PDRectangle(920,310));doc.addPage(page);
                    try(var stream=new PDPageContentStream(doc,page)){stream.drawImage(LosslessFactory.createFromImage(doc,plot.image()),0,0,920,310);}
                    doc.save(p.toFile());
                }
            }
        }
    }
    private static List<Object> values(Analysis.Result r){
        var s=r.stats();
        return List.of(r.column().title(),s.n(),r.rejected(),s.min(),s.max(),s.mean(),s.deviation(),s.q1(),s.median(),s.q3(),s.iqr(),s.mad(),s.iqrOutliers(),s.zOutliers());
    }
    static void table(Path p,List<Analysis.Result> results,TableFormat format)throws IOException {
        switch(format){
            case CSV -> {
                var csv=new StringBuilder("\ufeff");csv.append(csvRow(HEAD)).append('\n');
                for(var r:results)csv.append(csvRow(values(r))).append('\n');Files.writeString(p,csv);
            }
            case MD -> {
                var md=new StringBuilder(mdRow(HEAD)).append('\n').append(mdRow(Collections.nCopies(HEAD.size(),"---"))).append('\n');
                for(var r:results)md.append(mdRow(values(r))).append('\n');Files.writeString(p,md);
            }
            case JSON -> {
                StringJoiner rows=new StringJoiner(",\n","[\n","\n]\n");
                for(var r:results){
                    var cells=values(r);StringJoiner obj=new StringJoiner(",","  {","}");
                    for(int i=0;i<cells.size();i++)obj.add(json(HEAD.get(i))+":"+json(cells.get(i)));rows.add(obj.toString());
                }
                Files.writeString(p,rows.toString());
            }
            case XLSX -> {
                try(var book=new XSSFWorkbook()){
                    var sheet=book.createSheet("Статистика");var h=sheet.createRow(0);
                    for(int j=0;j<HEAD.size();j++)h.createCell(j).setCellValue(HEAD.get(j));
                    for(int i=0;i<results.size();i++){
                        var row=sheet.createRow(i+1);var data=values(results.get(i));
                        for(int j=0;j<data.size();j++){
                            var c=row.createCell(j);Object val=data.get(j);
                            if(val instanceof Number n)c.setCellValue(n.doubleValue());else c.setCellValue(val.toString());
                        }
                    }
                    sheet.createFreezePane(1,1);
                    try(var out=Files.newOutputStream(p)){book.write(out);}
                }
            }
        }
    }
    private static String csvRow(List<?> list){
        return String.join(",",list.stream().map(o->{
            String s=o.toString();
            if(o instanceof String&&s.stripLeading().matches("(?s)^[=+@-].*"))s="'"+s;
            return "\""+s.replace("\"","\"\"")+"\"";
        }).toList());
    }
    private static String mdRow(List<?> row){return "| "+String.join(" | ",row.stream().map(v->v.toString().replace("|","&#124;").replaceAll("[\\r\\n]+"," ")).toList())+" |";}
    private static String json(Object o){
        if(o instanceof Number||o instanceof List<?>)return o.toString();
        var b=new StringBuilder("\"");
        for(char c:o.toString().toCharArray())switch(c){
            case '\\' -> b.append("\\\\");case '"' -> b.append("\\\"");
            default -> {if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}
        }
        return b.append('"').toString();
    }
}
