package cscc43.cli;

import cscc43.auth.AuthContext;
import cscc43.model.DailyPrice;
import cscc43.service.PriceService;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.Callable;

/**
 * Commands for loading and maintaining price history.
 */
@CommandLine.Command(name = "prices", description = "Manage price history", subcommands = {
    PricesCommand.Add.class,
    PricesCommand.ImportHistoric.class
})
public final class PricesCommand implements Runnable {

    private final AuthContext authContext;
    private final PriceService priceService;

    public PricesCommand(AuthContext authContext, PriceService priceService) {
        this.authContext = authContext;
        this.priceService = priceService;
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    PriceService priceService() {
        return priceService;
    }

    long currentUserId() {
        return authContext.requireUser().id();
    }

    @CommandLine.Command(name = "add", description = "Insert or update a daily price entry")
    public static final class Add implements Callable<Integer> {

        @CommandLine.ParentCommand
        PricesCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--symbol", required = true)
        String symbol;

        @CommandLine.Option(names = "--day", required = true, description = "Trade date (YYYY-MM-DD)")
        LocalDate day;

        @CommandLine.Option(names = "--open", required = true)
        BigDecimal open;

        @CommandLine.Option(names = "--high", required = true)
        BigDecimal high;

        @CommandLine.Option(names = "--low", required = true)
        BigDecimal low;

        @CommandLine.Option(names = "--close", required = true)
        BigDecimal close;

        @CommandLine.Option(names = "--volume", required = true)
        long volume;

        @Override
        public Integer call() {
            DailyPrice price = new DailyPrice(symbol, day, open, high, low, close, volume);
            parent.priceService().insertNewPrice(price, parent.currentUserId());
            PrintWriter out = spec.commandLine().getOut();
            out.printf("Recorded price for %s on %s%n", symbol.toUpperCase(), day);
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "import", description = "Bulk load a CSV of historic prices")
    public static final class ImportHistoric implements Callable<Integer> {

        @CommandLine.ParentCommand
        PricesCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--file", required = true, description = "Path to CSV file")
        Path file;

        @Override
        public Integer call() throws Exception {
            try {
                parent.priceService().loadHistoricCsv(file);
            } catch (IOException exception) {
                throw new CommandLine.ExecutionException(spec.commandLine(), "Failed to import CSV", exception);
            }
            PrintWriter out = spec.commandLine().getOut();
            out.printf("Imported prices from %s%n", file);
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }
}
