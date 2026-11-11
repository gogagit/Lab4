package ru.semenyutin.lab4;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.nio.file.*;
import java.util.*;

final class Analysis {
    record Request(Path path,int sheet,List<Integer> columns,int first,int last,boolean skipBad,boolean sample,double multiplier,double zThreshold) {
        Request {
            columns=List.copyOf(columns);
            if(columns.isEmpty()||first<2||last<first||last>1048576||last-first>100000)throw new IllegalArgumentException("Выберите столбцы и корректный диапазон строк (до 100001 строки).");
            if(!Double.isFinite(multiplier)||multiplier<=0||!Double.isFinite(zThreshold)||zThreshold<=0)
                throw new IllegalArgumentException("Пороги должны быть конечными положительными числами.");
        }
    }
    record Column(int index,String title){public String toString(){return title;}}
    record SheetInfo(String name,List<Column> columns,int last){public String toString(){return name;}}
    record CellValue(int row,String value){}
    record Raw(Column column,List<CellValue> cells){}
    record Loaded(Request request,List<Raw> columns){}
    record Numbers(Column column,List<Double> values,int rejected){}
    record Prepared(Request request,List<Numbers> columns){}
    record Stats(int n,double min,double max,double mean,double deviation,double q1,double median,double q3,double iqr,double lowWhisker,double highWhisker,double mad,List<Double> iqrOutliers,List<Double> zOutliers){}
    record Result(Column column,List<Double> values,int rejected,Stats stats){}
    static final Pipeline<Request,List<Result>> PIPELINE=Pipeline.first(Analysis::load).then(Analysis::parse).then(Analysis::prepare).then(Analysis::compute);
    private Analysis(){}
    static List<SheetInfo> inspect(Path p)throws IOException {
        try(var in=Files.newInputStream(p);var w=WorkbookFactory.create(in)){
            List<SheetInfo> result=new ArrayList<>();
            for(Sheet s:w){
                Row header=s.getRow(0);List<Column> columns=new ArrayList<>();
                if(header!=null)for(int i=0;i<header.getLastCellNum();i++)columns.add(column(header,i));
                result.add(new SheetInfo(s.getSheetName(),List.copyOf(columns),s.getLastRowNum()+1));
            }
            return List.copyOf(result);
        }
    }
    private static Column column(Row header,int i){
        String name=header==null?"":new DataFormatter().formatCellValue(header.getCell(i)).strip();
        return new Column(i,CellReferenceName.of(i)+(name.isEmpty()?"":" · "+name));
    }
    private static final class CellReferenceName{
        static String of(int i){return org.apache.poi.ss.util.CellReference.convertNumToColString(i);}
    }
    private static Loaded load(Request q)throws IOException {
        try(var in=Files.newInputStream(q.path());var w=WorkbookFactory.create(in)){
            if(q.sheet()<0||q.sheet()>=w.getNumberOfSheets())throw new IllegalArgumentException("Лист не найден.");
            Sheet s=w.getSheetAt(q.sheet());var evaluator=w.getCreationHelper().createFormulaEvaluator();
            List<Raw> result=new ArrayList<>();
            for(int col:q.columns()){
                if(col<0||col>=w.getSpreadsheetVersion().getMaxColumns())throw new IllegalArgumentException("Некорректный столбец.");
                List<CellValue> values=new ArrayList<>();
                for(int i=q.first();i<=q.last();i++){
                    Row row=s.getRow(i-1);
                    values.add(new CellValue(i,cell(row==null?null:row.getCell(col),evaluator)));
                }
                result.add(new Raw(column(s.getRow(0),col),values));
            }
            return new Loaded(q,result);
        }
    }
    private static String cell(Cell c,FormulaEvaluator eval){
        if(c==null)return "";
        try{
            if(c.getCellType()==CellType.FORMULA){
                var v=eval.evaluate(c);
                if(v==null)return "";
                if(v.getCellType()==CellType.NUMERIC&&!DateUtil.isCellDateFormatted(c))return Double.toString(v.getNumberValue());
                if(v.getCellType()==CellType.STRING)return v.getStringValue();
                return "";
            }
            if(c.getCellType()==CellType.NUMERIC&&!DateUtil.isCellDateFormatted(c))return Double.toString(c.getNumericCellValue());
            return c.getCellType()==CellType.STRING?c.getStringCellValue():"";
        }catch(RuntimeException e){return "";}
    }
    private static Prepared parse(Loaded data){
        List<Numbers> result=new ArrayList<>();
        for(Raw col:data.columns()){
            List<Double> numbers=new ArrayList<>();int rejected=0;
            for(CellValue c:col.cells())try{
                String v=c.value().strip().replace(" ","").replace("\u00a0","").replace("\u202f","").replace(',','.');
                if(!v.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))throw new NumberFormatException();
                double x=Double.parseDouble(v);
                if(!Double.isFinite(x))throw new NumberFormatException();
                numbers.add(x);
            }catch(NumberFormatException e){
                if(!data.request().skipBad())throw new IllegalArgumentException(col.column().title()+": строка "+c.row()+" не содержит числа.");
                rejected++;
            }
            result.add(new Numbers(col.column(),numbers,rejected));
        }
        return new Prepared(data.request(),result);
    }
    private static Prepared prepare(Prepared data){
        List<Numbers> result=new ArrayList<>();
        for(Numbers c:data.columns()){
            int required=data.request().sample()?2:1;
            if(c.values().size()<required)throw new IllegalArgumentException(c.column().title()+": нужно минимум "+required+" числовых наблюдения.");
            result.add(new Numbers(c.column(),c.values().stream().sorted().toList(),c.rejected()));
        }
        return new Prepared(data.request(),result);
    }
    private static List<Result> compute(Prepared data){
        return data.columns().stream().map(c->new Result(c.column(),c.values(),c.rejected(),
            stats(c.values(),data.request().sample(),data.request().multiplier(),data.request().zThreshold()))).toList();
    }
    static Stats stats(List<Double> sorted,boolean sample,double m,double z){
        int n=sorted.size();if(n<(sample?2:1))throw new IllegalArgumentException("Недостаточно наблюдений.");
        double mean=0,s=0;int k=0;
        for(double x:sorted){double delta=x-mean;mean+=delta/++k;s+=delta*(x-mean);}
        double d=Math.sqrt(Math.max(0,s)/(sample?n-1:n)),q1=quantile(sorted,.25),median=quantile(sorted,.5),q3=quantile(sorted,.75),iqr=q3-q1;
        double lo=q1-m*iqr,hi=q3+m*iqr,avg=mean;
        double mad=sorted.stream().mapToDouble(x->Math.abs(x-avg)/n).sum();
        if(!Double.isFinite(mean)||!Double.isFinite(d)||!Double.isFinite(iqr)||!Double.isFinite(mad)||!Double.isFinite(lo)||!Double.isFinite(hi))throw new IllegalArgumentException("Числа слишком велики для расчёта.");
        var out=sorted.stream().filter(x->x<lo||x>hi).toList();
        var zOut=d==0?List.<Double>of():sorted.stream().filter(x->Math.abs((x-avg)/d)>z).toList();
        double whiskerLow=sorted.stream().filter(x->x>=lo&&x<=hi).findFirst().orElse(q1);
        double whiskerHigh=sorted.reversed().stream().filter(x->x>=lo&&x<=hi).findFirst().orElse(q3);
        return new Stats(n,sorted.getFirst(),sorted.getLast(),mean,d,q1,median,q3,iqr,whiskerLow,whiskerHigh,mad,out,zOut);
    }
    private static double quantile(List<Double> sorted,double p){
        double pos=(sorted.size()-1)*p;int i=(int)pos;
        if(i==sorted.size()-1)return sorted.get(i);
        return sorted.get(i)*(1-(pos-i))+sorted.get(i+1)*(pos-i);
    }
    static void sample(Path p)throws IOException {
        try(var w=new XSSFWorkbook()){
            var s=w.createSheet("Измерения");var h=s.createRow(0);h.createCell(0).setCellValue("Температура");h.createCell(1).setCellValue("Влажность");
            double[] values={16,17,17,18,18,19,19,20,20,21,22,39};
            for(int i=0;i<values.length;i++){var r=s.createRow(i+1);r.createCell(0).setCellValue(values[i]);r.createCell(1).setCellValue(40+(i*7)%13);}
            try(var out=Files.newOutputStream(p)){w.write(out);}
        }
    }
}
