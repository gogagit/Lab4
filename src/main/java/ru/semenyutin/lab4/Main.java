package ru.semenyutin.lab4;
import javafx.application.Application;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public final class Main extends Application {
    private final ComboBox<Analysis.SheetInfo> sheets=new ComboBox<>();
    private final ListView<Analysis.Column> columns=new ListView<>();
    private final TextField first=new TextField("2"),last=new TextField("2"),iqr=new TextField(),z=new TextField();
    private final CheckBox skip=new CheckBox("Пропускать некорректные ячейки"),sample=new CheckBox("Дисперсия: делитель n−1");
    private final ComboBox<Analysis.Result> chartColumn=new ComboBox<>();
    private final ComboBox<String> chartMode=new ComboBox<>();
    private final TableView<Analysis.Result> table=new TableView<>();
    private final ImageView view=new ImageView();
    private final Label note=new Label("Откройте таблицу или загрузите демонстрационный набор.");
    private final VBox controls=new VBox(10),exports=new VBox(10);
    private List<Analysis.Result> results=List.of();
    private Path file;
    private Stage stage;
    private boolean busy;
    public static void main(String[] args){launch(args);}
    @Override public void start(Stage window)throws Exception{
        stage=window;
        Properties cfg=new Properties();try(var in=new InputStreamReader(getClass().getResourceAsStream("/application.properties"),StandardCharsets.UTF_8)){cfg.load(in);}
        iqr.setText(cfg.getProperty("iqr.multiplier"));z.setText(cfg.getProperty("z.threshold"));skip.setSelected(true);sample.setSelected(true);
        chartMode.getItems().addAll("Ящик с усами","Скрипичный график");chartMode.getSelectionModel().selectFirst();
        chartColumn.setCellFactory(v->resultCell());chartColumn.setButtonCell(resultCell());
        chartColumn.valueProperty().addListener((o,a,b)->draw());chartMode.valueProperty().addListener((o,a,b)->draw());
        columns.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);columns.setPrefHeight(120);
        sheets.valueProperty().addListener((o,a,b)->{
            if(b!=null){columns.getItems().setAll(b.columns());columns.getSelectionModel().selectFirst();first.setText("2");last.setText(""+b.last());}
            clearResults();
        });
        var open=Fx.button("Открыть Excel",()->{
            Path p=Fx.file(stage,false,"Открыть Excel","*.xlsx","*.xls");if(p!=null)load(p);
        });
        var demo=Fx.button("Загрузить пример",()->{
            Path p=Path.of("examples","measurements.xlsx");
            background("Открываем пример…",()->{
                if(Files.notExists(p)){Files.createDirectories(p.getParent());Analysis.sample(p);}
                return Analysis.inspect(p);
            },info->setFile(p,info));
        });
        var saveDemo=Fx.button("Сохранить пример",()->{
            Path p=Fx.file(stage,true,"Сохранить демонстрационный Excel","*.xlsx");
            if(p!=null)background("Сохраняем пример…",()->{Analysis.sample(p);return p;},v->note.setText("Пример сохранён: "+v.getFileName()));
        });
        var analyze=Fx.button("Рассчитать",this::analyze);analyze.getStyleClass().add("accent");
        first.setPrefColumnCount(6);last.setPrefColumnCount(6);iqr.setPrefColumnCount(5);z.setPrefColumnCount(5);
        controls.getChildren().addAll(new HBox(8,open,demo,saveDemo),new HBox(8,new Label("Лист"),sheets),
            new Label("Столбцы (Ctrl / Shift для выбора нескольких)"),columns,
            new HBox(7,new Label("Со строки"),first,new Label("по"),last),
            new HBox(7,new Label("IQR ×"),iqr,new Label("Порог |Z|"),z),
            skip,sample,analyze);
        controls.getStyleClass().add("panel");
        table.getColumns().addAll(col("Столбец",r->r.column().title()),col("N",r->""+r.stats().n()),
            col("Пропуски",r->""+r.rejected()),col("Среднее",r->fmt(r.stats().mean())),col("Отклонение",r->fmt(r.stats().deviation())),
            col("Мин.",r->fmt(r.stats().min())),col("Макс.",r->fmt(r.stats().max())),col("Q1",r->fmt(r.stats().q1())),
            col("Медиана",r->fmt(r.stats().median())),col("Q3",r->fmt(r.stats().q3())),col("IQR",r->fmt(r.stats().iqr())),
            col("Ср. абс. откл.",r->fmt(r.stats().mad())),col("Выбросы IQR",r->brief(r.stats().iqrOutliers())),
            col("Выбросы Z",r->brief(r.stats().zOutliers())));
        table.setPrefHeight(210);
        view.setPreserveRatio(true);view.setFitHeight(240);
        var chartFormat=new ComboBox<Export.ChartFormat>();chartFormat.getItems().setAll(Export.ChartFormat.values());chartFormat.getSelectionModel().selectFirst();
        var tableFormat=new ComboBox<Export.TableFormat>();tableFormat.getItems().setAll(Export.TableFormat.values());tableFormat.getSelectionModel().selectFirst();
        exports.getChildren().addAll(new HBox(8,chartFormat,Fx.button("Сохранить график",()->exportChart(chartFormat.getValue())),
            tableFormat,Fx.button("Сохранить таблицу",()->exportTable(tableFormat.getValue()))));
        var right=new VBox(8,new HBox(8,chartColumn,chartMode),view,table,exports);
        VBox.setVgrow(table,Priority.ALWAYS);right.widthProperty().addListener((o,a,b)->view.setFitWidth(Math.max(200,b.doubleValue()-12)));
        var split=new SplitPane(controls,right);split.setDividerPositions(.30);
        var layout=new VBox(12,Fx.title("Спектр · статистический анализатор"),Fx.button("Справка",this::help),split,note);
        layout.setPadding(new Insets(14));note.setWrapText(true);VBox.setVgrow(split,Priority.ALWAYS);
        clearResults();Fx.show(stage,"Лабораторная 4 · Спектр",layout,1280,730);
    }
    private void load(Path p){background("Читаем Excel…",()->Analysis.inspect(p),info->setFile(p,info));}
    private void setFile(Path p,List<Analysis.SheetInfo> info){
        file=p;sheets.getItems().setAll(info);sheets.getSelectionModel().selectFirst();clearResults();note.setText("Файл: "+p.getFileName());
    }
    private ListCell<Analysis.Result> resultCell(){return new ListCell<>(){protected void updateItem(Analysis.Result r,boolean empty){super.updateItem(r,empty);setText(r==null||empty?null:r.column().title());}};}
    private TableColumn<Analysis.Result,String> col(String title,java.util.function.Function<Analysis.Result,String> f){var c=new TableColumn<Analysis.Result,String>(title);c.setCellValueFactory(v->new ReadOnlyStringWrapper(f.apply(v.getValue())));return c;}
    private void analyze(){
        try{
            if(file==null||sheets.getValue()==null)throw new IllegalArgumentException("Сначала выберите файл и лист.");
            var q=new Analysis.Request(file,sheets.getSelectionModel().getSelectedIndex(),columns.getSelectionModel().getSelectedItems().stream().map(Analysis.Column::index).toList(),
                Integer.parseInt(first.getText().strip()),Integer.parseInt(last.getText().strip()),skip.isSelected(),sample.isSelected(),decimal(iqr),decimal(z));
            clearResults();
            background("Анализируем данные…",()->Analysis.PIPELINE.run(q),data->{
                results=data;table.setItems(FXCollections.observableArrayList(data));chartColumn.getItems().setAll(data);
                chartColumn.getSelectionModel().selectFirst();exports.setDisable(false);
                note.setText("Столбцов: "+data.size()+" · пропущено ячеек: "+data.stream().mapToInt(Analysis.Result::rejected).sum());
            });
        }catch(Exception e){Fx.error(e);}
    }
    private static double decimal(TextField field){return Double.parseDouble(field.getText().strip().replace(',','.'));}
    private void clearResults(){results=List.of();chartColumn.getItems().clear();table.getItems().clear();view.setImage(null);exports.setDisable(true);}
    private boolean violin(){return chartMode.getSelectionModel().getSelectedIndex()==1;}
    private void draw(){
        var selected=chartColumn.getValue();if(selected==null){view.setImage(null);return;}
        try{view.setImage(new Image(new ByteArrayInputStream(Plot.render(selected,violin()).png())));}catch(Exception e){Fx.error(e);}
    }
    private void exportChart(Export.ChartFormat kind){
        var selected=chartColumn.getValue();if(selected==null)return;
        boolean v=violin();
        Path p=saveAs(kind.name());if(p!=null)background("Сохраняем график…",()->{Export.chart(p,Plot.render(selected,v),kind);return p;},q->note.setText("Сохранено: "+q.getFileName()));
    }
    private void exportTable(Export.TableFormat kind){
        if(results.isEmpty())return;
        Path p=saveAs(kind.name());var rows=results;if(p!=null)background("Сохраняем таблицу…",()->{Export.table(p,rows,kind);return p;},q->note.setText("Сохранено: "+q.getFileName()));
    }
    private Path saveAs(String format){
        String ext="."+format.toLowerCase(Locale.ROOT);Path p=Fx.file(stage,true,"Сохранить "+format,"*"+ext);
        return p==null?null:p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ext)?p:p.resolveSibling(p.getFileName()+ext);
    }
    private <T> void background(String message,Callable<T> work,Consumer<T> success){
        if(busy)return;busy=true;controls.setDisable(true);exports.setDisable(true);note.setText(message);
        Task<T> task=new Task<>(){protected T call()throws Exception{return work.call();}};
        task.setOnSucceeded(e->{finish();success.accept(task.getValue());});
        task.setOnFailed(e->{finish();note.setText("Операция не завершена.");Fx.error(task.getException());});
        Thread.ofVirtual().start(task);
    }
    private void finish(){busy=false;controls.setDisable(false);exports.setDisable(results.isEmpty());}
    private static String fmt(double n){return String.format(Locale.ROOT,"%.5g",n);}
    private static String brief(List<Double> list){return list.size()>5?list.subList(0,5)+" … ("+list.size()+")":list.toString();}
    private void help(){
        Alert a=new Alert(Alert.AlertType.INFORMATION);a.initOwner(stage);a.setHeaderText("Как выполнить анализ");
        a.setContentText("Откройте Excel. Первая строка — заголовки. Выберите лист, один или несколько столбцов и диапазон строк со второй. Нажмите «Рассчитать».\n\nIQR — разность Q3 и Q1; значения за границами Q1−k·IQR и Q3+k·IQR отмечены красным. Z-score — расстояние от среднего в стандартных отклонениях. Выберите тип графика и формат сохранения.");
        a.show();
    }
}
