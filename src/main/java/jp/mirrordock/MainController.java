package jp.mirrordock;
import javafx.application.Platform;
import javafx.animation.*;
import javafx.util.Duration;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.io.IOException;
import java.nio.file.Path;
public final class MainController implements AutoCloseable {
    private final Stage stage;
    private final SettingsService store=new SettingsService(SettingsService.userDirectory());
    private final LogService log=new LogService(SettingsService.userDirectory());
    private final UxPlayService airplay=new UxPlayService();
    private final WindowManager windows=new WindowManager();
    private final Label status=new Label("停止中"), message=new Label(), feedback=new Label();
    private final TextField name=new TextField(), executable=new TextField();
    private final CheckBox onTop=new CheckBox("最前面に固定"), audio=new CheckBox("音声を再生"),autoStart=new CheckBox("起動時に受信開始"),logging=new CheckBox("状態ログを保存（端末情報は保存しません）");
    private final Button start=new Button("受信開始"),stop=new Button("停止"),retry=new Button("再試行");
    private final TitledPane settings=new TitledPane();
    private final Timeline nativeSync;
    private Settings loaded;
    private double normalX,normalY,normalWidth,normalHeight;
    public MainController(Stage stage) {
        this.stage=stage;
        String loadError="";
        try { loaded=store.load(); } catch (IOException e) {loaded=Settings.defaults();loadError="設定を読み込めませんでした。初期設定で起動します。";}
        name.setText(loaded.receiverName());executable.setText(loaded.executable());onTop.setSelected(loaded.onTop());audio.setSelected(loaded.audio());autoStart.setSelected(loaded.autoStart());logging.setSelected(loaded.logging());
        log.setEnabled(loaded.logging());
        Label title=new Label("MirrorDock");title.getStyleClass().add("title");
        Label subtitle=new Label("iPhone の画面を、この PC へ。");
        message.setWrapText(true);feedback.setWrapText(true);
        Label instruction=new Label("iPhoneのコントロールセンターを開き、画面ミラーリングからMirrorDockを選択してください。");instruction.setWrapText(true);
        name.textProperty().addListener((o,a,b)->instruction.setText("iPhoneのコントロールセンターを開き、画面ミラーリングから"+b+"を選択してください。"));
        Button choose=new Button("選択…");choose.setOnAction(e -> {
            FileChooser fc=new FileChooser();fc.setTitle("信頼できるUxPlay実行ファイルを選択");
            if (System.getProperty("os.name").toLowerCase().contains("win")) fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("UxPlay","uxplay.exe"));
            var f=fc.showOpenDialog(stage);if(f!=null) executable.setText(f.getAbsolutePath());
        });
        HBox path=new HBox(8,executable,choose);HBox.setHgrow(executable,Priority.ALWAYS);
        Button save=new Button("設定を保存");save.setOnAction(e -> save());
        VBox config=new VBox(12,new Label("受信機名（変更は次の受信開始時に反映）"),name,new Label("UxPlay 実行ファイル（信頼できる uxplay.exe のみ）"),path,autoStart,logging,save);
        settings.setText("設定");settings.setContent(config);settings.setExpanded(loaded.executable().isBlank());
        Button help=new Button("接続できないとき");help.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION,"PCとiPhoneを同じLANに接続してください。有線LANとWi-Fiの組合せも利用できます。\n\nゲストWi-Fi・AP隔離・VPN、Windowsのプライベートネットワーク設定、UxPlayのファイアウォール許可、mDNS（UDP 5353）を確認してください。Bonjour方式ではBonjourサービスも確認します。\n\nDLL不足・映像エラー：MSYS2 UCRT64のGStreamer base/good/bad/libavを確認してください。音声エラーでは音声をOFFにして受信を再開してください。\n\n原因を特定できない場合はINSTALL.mdの診断手順を参照してください。ファイアウォールは自動変更しません。",ButtonType.OK).show());
        Label video=new Label("映像は UxPlay の別ウィンドウに表示されます。\nウィンドウは自由にサイズ変更できます。\nWindows の映像全画面切替：映像ウィンドウで Alt + Enter");video.setWrapText(true);
        VBox content=new VBox(18,title,subtitle,status,message,new HBox(10,start,stop,retry),instruction,video,new HBox(18,onTop,audio),settings,help,feedback);
        content.setPadding(new Insets(28));content.setFillWidth(true);
        ScrollPane scroll=new ScrollPane(content);scroll.setFitToWidth(true);
        Scene scene=new Scene(scroll,900,660);scene.getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());
        stage.setTitle("MirrorDock");stage.setScene(scene);stage.setMinWidth(640);stage.setMinHeight(520);windows.restore(stage,loaded);
        normalX=stage.getX();normalY=stage.getY();normalWidth=stage.getWidth();normalHeight=stage.getHeight();
        javafx.beans.InvalidationListener geometry=o -> {if(!stage.isMaximized()&&!stage.isFullScreen()&&!stage.isIconified()){normalX=stage.getX();normalY=stage.getY();normalWidth=stage.getWidth();normalHeight=stage.getHeight();}};
        stage.xProperty().addListener(geometry);stage.yProperty().addListener(geometry);stage.widthProperty().addListener(geometry);stage.heightProperty().addListener(geometry);
        start.setOnAction(e -> begin());retry.setOnAction(e -> {airplay.stop();begin();});stop.setOnAction(e -> airplay.stop());
        onTop.setOnAction(e -> {stage.setAlwaysOnTop(onTop.isSelected());save();});
        audio.setOnAction(e -> {save();feedback.setText("音声設定は次の受信開始時に反映します。音声OFFでも映像は受信します。");});
        stop.setDisable(true);retry.setVisible(false);
        airplay.setListener(event -> Platform.runLater(() -> {
            status.setText(switch(event.state()){case STOPPED->"停止中";case STARTING->"起動中";case WAITING->"接続待機中";case CONNECTED->"接続中";case ERROR->"エラー";});
            message.setText(event.message());log.status(event.state());
            boolean busy=event.state()!=AirPlayService.State.STOPPED && event.state()!=AirPlayService.State.ERROR;
            start.setDisable(busy);stop.setDisable(!busy);retry.setVisible(event.state()==AirPlayService.State.ERROR);
            name.setDisable(busy);executable.setDisable(busy);choose.setDisable(busy);
            if(event.state()==AirPlayService.State.CONNECTED) settings.setExpanded(false);
        }));
        nativeSync=new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            try {windows.engineOnTop(airplay.pid(),onTop.isSelected());}
            catch (RuntimeException | LinkageError err) {feedback.setText("映像ウィンドウの最前面制御に失敗しました。受信は継続します。");}
        }));nativeSync.setCycleCount(Animation.INDEFINITE);
        if(!loadError.isEmpty()) feedback.setText(loadError);
    }
    private Settings snapshot() {return new Settings(name.getText(),executable.getText(),onTop.isSelected(),audio.isSelected(),autoStart.isSelected(),logging.isSelected(),normalX,normalY,normalWidth,normalHeight);}
    private boolean save() {
        if (!snapshot().receiverName().equals(name.getText())) {feedback.setText("受信機名は1〜80文字で入力してください（制御文字は使用できません）。");return false;}
        try {store.save(snapshot());log.setEnabled(logging.isSelected());feedback.setText("設定を保存しました。");return true;}
        catch(IOException e){feedback.setText("設定を保存できません。保存先の書き込み権限を確認してください。");return false;}
    }
    private void begin(){if(save()) airplay.start(snapshot());}
    public void show(){stage.show();nativeSync.play();if(loaded.autoStart())begin();
        if(Boolean.getBoolean("mirrordock.smoke")) {
            begin();
            PauseTransition exit=new PauseTransition(Duration.seconds(2));exit.setOnFinished(e->{
                if(!stage.isShowing() || airplay.state()!=AirPlayService.State.ERROR || !status.getText().equals("エラー")) {System.err.println("GUI_SMOKE_FAILED");System.exit(2);}
                System.out.println("GUI_SMOKE_OK: stage visible; missing-engine error displayed");Platform.exit();
            });exit.play();
        }}
    public void close(){nativeSync.stop();save();airplay.close();}
}
