package ca.yorku.eecs3311.consultant.app;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/**
 * JavaFX entry point for the desktop GUI.
 *
 * <p>The GUI and the CLI are two clients of the same application core; neither
 * holds domain logic of its own. See {@code docs/STAGE1.md} section 3.
 */
public class App extends Application {

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.setCenter(new Label("Project-Aware Research Consultant"));

        stage.setTitle("Project-Aware Research Consultant");
        stage.setScene(new Scene(root, 1100, 720));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
