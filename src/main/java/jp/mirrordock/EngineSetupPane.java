package jp.mirrordock;

import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.*;

/** User initiated local import and diagnostics; no background downloads or policy changes. */
final class EngineSetupPane extends TitledPane implements AutoCloseable {
    private final ExecutorService tasks=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"engine-setup");t.setDaemon(true);return t;});
    private final Label result=new Label(), selectionStatus=new Label();
    private final Button install=new Button("エンジンZIPを導入"), find=new Button("インストール済みを探す"), diagnose=new Button("受信エンジンを診断");
    private boolean working, receiving;

    EngineSetupPane(Window owner, Supplier<String> selected, Function<String,Boolean> select, Consumer<String> openUri) {
        setText("受信エンジンの導入・診断");
        Label steps=new Label("初回：配布ページ → 成功したCIのArtifactsをダウンロード・展開 → 内側のMirrorDock-Engine-Windows.zipを選択します。\nMSYS2の導入やソースのビルドは不要です。エンジン成果物がまだ生成されていない場合は利用できません。");
        steps.setWrapText(true);
        Label notice=new Label("この方式は実験版です。Windows・iPhoneでの受信は未検証で、動作を保証しません。\nMirrorDock公式リポジトリのCIから取得したZIPだけを選択してください。チェックサムは破損を検出しますが、配布者を認証する電子署名ではありません。");
        notice.setWrapText(true);result.setWrapText(true);selectionStatus.setWrapText(true);
        Button download=new Button("エンジン配布ページを開く");
        download.setOnAction(e->openUri.accept("https://github.com/sunnytruck/MirrorDock/actions/workflows/engine.yml"));
        Button firewall=new Button("Windowsのファイアウォール画面を開く");
        firewall.setDisable(!System.getProperty("os.name").toLowerCase().contains("win"));
        firewall.setOnAction(e->openUri.accept("windowsdefender://network/"));
        install.setOnAction(e->{
            FileChooser chooser=new FileChooser();chooser.setTitle("MirrorDock CIのエンジンZIPを選択");chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("MirrorDock Engine ZIP","*.zip"));
            var file=chooser.showOpenDialog(owner);if(file==null)return;
            run(()->{
                var installed=new EngineBundleService().install(file.toPath(),SettingsService.userDirectory());
                Platform.runLater(()->selectionStatus.setText(select.apply(installed.executable().toString()) ? "実行ファイルの設定を保存しました。" : "実行ファイル欄には反映しましたが、設定を保存できませんでした。保存先の権限を確認して設定を保存してください。"));
                return "エンジンを導入し、実行ファイル欄に反映しました。続いて「受信エンジンを診断」、最後に「受信開始」で実機接続を確認してください。以前の導入ファイルは保持しています。";
            });
        });
        find.setOnAction(e->run(()->{
            var paths=EngineDiagnostics.candidatePaths(Path.of(System.getProperty("user.dir")),System.getenv("LOCALAPPDATA"),System.getenv("SystemDrive"));
            var found=EngineDiagnostics.findInstalled(paths);
            if(found.isEmpty())return EngineDiagnostics.missingExecutable().displayText();
            Platform.runLater(()->selectionStatus.setText(select.apply(found.get().toAbsolutePath().toString()) ? "実行ファイルの設定を保存しました。" : "実行ファイル欄には反映しましたが、設定を保存できませんでした。保存先の権限を確認してください。"));
            return "導入済みのUxPlayを実行ファイル欄に反映しました。実行はしていません。「受信エンジンを診断」で確認してください。";
        }));
        diagnose.setOnAction(e->{
            String value=selected.get();
            run(()->{
                Path executable=value==null||value.isBlank()?null:Path.of(value);
                var engine=EngineDiagnostics.inspectExecutable(executable,Duration.ofSeconds(8));
                if(engine.severity()==EngineDiagnostics.Severity.ERROR)return engine.displayText();
                var network=FirewallDiagnostics.inspect(executable,Duration.ofSeconds(10));
                return engine.displayText()+"\n\n"+network.message()+"\n"+network.solution();
            });
        });
        VBox content=new VBox(10,steps,notice,download,install,find,diagnose,firewall,selectionStatus,result);setContent(content);
    }
    private void run(Callable<String> operation) {
        if(working||receiving)return;working=true;updateButtons();result.setText("確認しています…");
        tasks.submit(()->{
            String text;
            try{text=operation.call();}
            catch(IOException e){text=e.getMessage()==null?"導入または診断に失敗しました。原因を特定できません。":e.getMessage();}
            catch(Exception e){text="導入または診断に失敗しました。保存先・ZIP・実行ファイルを確認してください。原因を特定できません。";}
            String display=text;
            Platform.runLater(()->{working=false;updateButtons();result.setText(display);});
        });
    }
    boolean isWorking(){return working;}
    void setReceiving(boolean receiving){this.receiving=receiving;updateButtons();}
    private void updateButtons(){install.setDisable(working||receiving);find.setDisable(working||receiving);diagnose.setDisable(working||receiving);}
    @Override public void close(){tasks.shutdownNow();}
}
