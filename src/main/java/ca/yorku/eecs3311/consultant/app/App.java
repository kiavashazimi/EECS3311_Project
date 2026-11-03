package ca.yorku.eecs3311.consultant.app;

import ca.yorku.eecs3311.consultant.agent.ToolManager;
import ca.yorku.eecs3311.consultant.config.Config;
import ca.yorku.eecs3311.consultant.controller.AppController;
import ca.yorku.eecs3311.consultant.literature.ArxivSource;
import ca.yorku.eecs3311.consultant.llm.ClaudeClient;
import ca.yorku.eecs3311.consultant.memory.MemoryStore;
import ca.yorku.eecs3311.consultant.memory.ProjectMemory;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * JavaFX entry point.
 *
 * <p>Builds the same object graph {@code Cli.main} does and hands it to
 * {@link MainGUI}. The GUI and the CLI are two clients of one core; neither
 * holds domain logic of its own. See {@code docs/STAGE1.md} section 3.
 */
public class App extends Application {

    @Override
    public void start(Stage stage) {
        Config config = Config.load();
        ToolManager tools = new ToolManager(new ClaudeClient(config));
        tools.setLiteratureSource(new ArxivSource());

        AppController controller = new AppController(
                ProjectMemory.getInstance(), tools, new MemoryStore(config.dataDir()));

        stage.setTitle("Project-Aware Research Consultant");
        stage.setScene(new Scene(new MainGUI(controller).build(), 1100, 720));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
