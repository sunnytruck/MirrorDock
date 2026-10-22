package jp.mirrordock;
import javafx.application.Application;
import javafx.stage.Stage;
public final class MainApplication extends Application {
    private MainController controller;
    public static void main(String[] args) { launch(args); }
    @Override public void start(Stage stage) { controller=new MainController(stage); controller.show(); }
    @Override public void stop() { if (controller!=null) controller.close(); }
}
