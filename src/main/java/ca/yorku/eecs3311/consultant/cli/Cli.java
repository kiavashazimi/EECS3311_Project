package ca.yorku.eecs3311.consultant.cli;

/**
 * Command-line entry point. Every major feature must be reachable from here as
 * well as from the GUI.
 */
public class Cli {

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }
        System.out.println("Unknown command: " + args[0]);
        printUsage();
    }

    static void printUsage() {
        System.out.println("""
                Project-Aware Research Consultant - command line

                Usage: rc <command> [options]

                Commands will be added as features are implemented.
                """);
    }
}
