package ca.yorku.eecs3311.consultant.app;

import ca.yorku.eecs3311.consultant.boot.Bootstrap;
import ca.yorku.eecs3311.consultant.controller.AppController;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * JavaFX entry point.
 *
 * <p>Takes its controller from {@link Bootstrap}, the same way {@code Cli.main}
 * does, and hands it to {@link MainGUI}. The GUI and the CLI are two clients of
 * one core; neither holds domain logic of its own, and neither wires itself up.
 * See {@code docs/STAGE1.md} section 3.
 */
public class App extends Application {

    @Override
    public void start(Stage stage) {
        AppController controller = Bootstrap.application();

        stage.setTitle("Project-Aware Research Consultant");
        stage.setScene(new Scene(new MainGUI(controller).build(), 1100, 720));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
