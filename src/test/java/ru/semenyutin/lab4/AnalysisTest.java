package ru.semenyutin.lab4;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisTest {
    @TempDir Path dir;
    @Test void knownQuartilesAndOutliers(){
        var s=Analysis.stats(List.of(1.,2.,3.,4.,5.,6.,7.,40.),true,1.5,3);
        assertEquals(2.75,s.q1());assertEquals(4.5,s.median());assertEquals(6.25,s.q3());
        assertEquals(7,s.highWhisker());assertEquals(List.of(40.),s.iqrOutliers());assertTrue(s.mad()>0);
    }
    @Test void bothVarianceDivisorsAndSingleValue(){
        assertEquals(1,Analysis.stats(List.of(1.,3.),false,1.5,3).deviation());
        assertEquals(Math.sqrt(2),Analysis.stats(List.of(1.,3.),true,1.5,3).deviation());
        assertEquals(0,Analysis.stats(List.of(1.),false,1.5,3).deviation());
        assertThrows(IllegalArgumentException.class,()->Analysis.stats(List.of(1.),true,1.5,3));
    }
    @Test void readsFormulasAndDecimalCommaAndRejectsText()throws Exception {
        var p=dir.resolve("input.xlsx");
        try(var w=new XSSFWorkbook()){
            var s=w.createSheet();s.createRow(0).createCell(0).setCellValue("Температура");
            s.createRow(1).createCell(0).setCellValue("1,5");
            s.createRow(2).createCell(0).setCellFormula("2*3");
            s.createRow(3).createCell(0).setCellValue("нет");
            try(var stream=Files.newOutputStream(p)){w.write(stream);}
        }
        var rows=Analysis.PIPELINE.run(new Analysis.Request(p,0,List.of(0),2,4,true,true,1.5,3));
        assertEquals(List.of(1.5,6.0),rows.getFirst().values());
        assertEquals(1,rows.getFirst().rejected());
        assertThrows(IllegalArgumentException.class,()->Analysis.PIPELINE.run(new Analysis.Request(p,0,List.of(0),2,4,false,true,1.5,3)));
    }
    @Test void exportsReadableFormats()throws Exception {
        var p=dir.resolve("sample.xlsx");Analysis.sample(p);
        var result=Analysis.PIPELINE.run(new Analysis.Request(p,0,List.of(0,1),2,13,true,true,1.5,3));
        assertEquals(2,result.size());assertEquals(List.of(39.),result.getFirst().stats().iqrOutliers());
        for(var f:Export.ChartFormat.values()){
            var path=dir.resolve("plot."+f.name());Export.chart(path,Plot.render(result.getFirst(),true),f);assertTrue(Files.size(path)>200);
            if(f==Export.ChartFormat.PNG)assertNotNull(javax.imageio.ImageIO.read(path.toFile()));
            if(f==Export.ChartFormat.PDF)try(var pdf=org.apache.pdfbox.Loader.loadPDF(path.toFile())){assertEquals(1,pdf.getNumberOfPages());}
            if(f==Export.ChartFormat.SVG)assertTrue(Files.readString(path).contains("<path"));
        }
        for(var f:Export.TableFormat.values()){
            var path=dir.resolve("table."+f.name());Export.table(path,result,f);assertTrue(Files.size(path)>80);
            if(f==Export.TableFormat.XLSX)try(var w=org.apache.poi.ss.usermodel.WorkbookFactory.create(path.toFile())){assertEquals(12,w.getSheetAt(0).getRow(1).getCell(1).getNumericCellValue());}
        }
    }
}
