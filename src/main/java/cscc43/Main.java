package cscc43;

import cscc43.auth.AuthContext;
import cscc43.cli.AnalyticsCommand;
import cscc43.cli.AuthCommand;
import cscc43.cli.FriendsCommand;
import cscc43.cli.ListsCommand;
import cscc43.cli.PortfolioCommand;
import cscc43.cli.PricesCommand;
import cscc43.cli.ReviewsCommand;
import cscc43.cli.RootCommand;
import cscc43.cli.UsersCommand;
import cscc43.config.DatabaseConfig;
import cscc43.db.Database;
import cscc43.db.DatabaseException;
import cscc43.service.AnalyticsService;
import cscc43.service.FriendService;
import cscc43.service.PortfolioService;
import cscc43.service.PriceService;
import cscc43.service.ReviewService;
import cscc43.service.StockListService;
import cscc43.service.StockService;
import cscc43.service.UserService;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;

/**
 * Entry point that wires together services and the Picocli-based interactive shell.
 */
public final class Main {

    private Main() {
        // no instantiation
    }

    public static void main(String[] args) {
        DatabaseConfig config = DatabaseConfig.fromEnv();
        try (Database database = new Database(config)) {
            AuthContext authContext = new AuthContext();
            UserService userService = new UserService(database);
            StockService stockService = new StockService(database);
            PortfolioService portfolioService = new PortfolioService(database, stockService);
            FriendService friendService = new FriendService(database);
            StockListService stockListService = new StockListService(database, stockService);
            ReviewService reviewService = new ReviewService(database, stockListService);
            PriceService priceService = new PriceService(database, stockService);
            AnalyticsService analyticsService = new AnalyticsService(database, portfolioService, stockListService, priceService);

            RootCommand root = new RootCommand(authContext, userService, portfolioService);
            CommandLine commandLine = new CommandLine(root);
            commandLine.addSubcommand("users", new UsersCommand(userService));
            commandLine.addSubcommand("auth", new AuthCommand(authContext, userService));
            commandLine.addSubcommand("portfolios", new PortfolioCommand(authContext, portfolioService));
            commandLine.addSubcommand("friends", new FriendsCommand(authContext, friendService, userService));
            commandLine.addSubcommand("lists", new ListsCommand(authContext, stockListService, userService));
            commandLine.addSubcommand("reviews", new ReviewsCommand(authContext, reviewService, userService));
            commandLine.addSubcommand("prices", new PricesCommand(authContext, priceService));
            commandLine.addSubcommand("analytics", new AnalyticsCommand(authContext, analyticsService, stockListService));
            commandLine.setCaseInsensitiveEnumValuesAllowed(true);
            commandLine.setOut(new PrintWriter(System.out, true));
            commandLine.setErr(new PrintWriter(System.err, true));
            commandLine.setExecutionExceptionHandler((exception, cmd, parseResult) -> {
                PrintWriter err = cmd.getErr();
                if (exception instanceof CommandLine.ParameterException parameterException) {
                    err.println(parameterException.getMessage());
                } else if (exception instanceof DatabaseException databaseException) {
                    err.println("Database error: " + databaseException.getMessage());
                } else if (exception instanceof IllegalArgumentException illegalArgumentException) {
                    err.println("Error: " + illegalArgumentException.getMessage());
                } else {
                    err.println("Unexpected error: " + exception.getMessage());
                }
                err.flush();
                return cmd.getCommandSpec().exitCodeOnExecutionException();
            });

            if (args.length > 0) {
                int exitCode = commandLine.execute(args);
                if (exitCode != CommandLine.ExitCode.OK) {
                    System.exit(exitCode);
                }
            } else {
                runInteractiveShell(commandLine);
            }
        }
    }

    private static void runInteractiveShell(CommandLine commandLine) {
        PrintWriter out = commandLine.getOut();
        PrintWriter err = commandLine.getErr();
        out.println("Connected to database. Type 'help' for options, 'exit' to quit.");
        out.flush();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (true) {
                out.print("cscc43> ");
                out.flush();
                String line = reader.readLine();
                if (line == null) {
                    out.println();
                    break;
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if ("exit".equalsIgnoreCase(trimmed) || "quit".equalsIgnoreCase(trimmed)) {
                    break;
                }
                if ("help".equalsIgnoreCase(trimmed)) {
                    commandLine.usage(out);
                    out.flush();
                    continue;
                }
                try {
                    String[] arguments = tokenize(trimmed);
                    int exitCode = commandLine.execute(arguments);
                    if (exitCode != CommandLine.ExitCode.OK) {
                        err.printf("Command exited with code %d%n", exitCode);
                    }
                } catch (CommandLine.UnmatchedArgumentException exception) {
                    err.println("Unknown command: " + exception.getUnmatched());
                } catch (Exception exception) {
                    err.println("Error: " + exception.getMessage());
                }
                err.flush();
            }
        } catch (IOException exception) {
            err.println("I/O error: " + exception.getMessage());
        }
        out.println("Goodbye");
        out.flush();
    }
    private static String[] tokenize(String input) {
        StringBuilder current = new StringBuilder();
        java.util.List<String> tokens = new java.util.ArrayList<>();
        boolean inQuotes = false;
        char quoteChar = 0;
        boolean escape = false;
        for (char ch : input.toCharArray()) {
            if (escape) {
                current.append(ch);
                escape = false;
                continue;
            }
            if (ch == '\\') {
                escape = true;
                continue;
            }
            if (inQuotes) {
                if (ch == quoteChar) {
                    inQuotes = false;
                    quoteChar = 0;
                } else {
                    current.append(ch);
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                inQuotes = true;
                quoteChar = ch;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens.toArray(new String[0]);
    }
}
