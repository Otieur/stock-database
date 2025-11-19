package cscc43.cli;

import cscc43.auth.AuthContext;
import cscc43.model.CorrelationEntry;
import cscc43.model.PortfolioStatistics;
import cscc43.model.PriceHistoryPoint;
import cscc43.model.PricePrediction;
import cscc43.model.SecurityStatistic;
import cscc43.model.StockListAccess;
import cscc43.service.AnalyticsService;
import cscc43.service.StockListService;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Commands for running portfolio and stock analytics.
 */
@CommandLine.Command(name = "analytics", description = "Run analytics on portfolios, lists, or symbols", subcommands = {
    AnalyticsCommand.PortfolioStats.class,
    AnalyticsCommand.ListStats.class,
    AnalyticsCommand.History.class,
    AnalyticsCommand.Predict.class
})
public final class AnalyticsCommand implements Runnable {

    private final AuthContext authContext;
    private final AnalyticsService analyticsService;
    private final StockListService stockListService;

    public AnalyticsCommand(AuthContext authContext, AnalyticsService analyticsService,
                            StockListService stockListService) {
        this.authContext = authContext;
        this.analyticsService = analyticsService;
        this.stockListService = stockListService;
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    long currentUserId() {
        return authContext.requireUser().id();
    }

    AnalyticsService analyticsService() {
        return analyticsService;
    }

    StockListService stockListService() {
        return stockListService;
    }

    static void renderStatistics(PrintWriter out, PortfolioStatistics statistics) {
        List<SecurityStatistic> securities = statistics.securities();
        if (securities.isEmpty()) {
            out.println("No symbols available for statistics");
        } else {
            out.println("Symbol | Avg Close | Volatility | COV | Beta");
            for (SecurityStatistic stat : securities) {
                out.printf("%s | %s | %s | %s | %s%n",
                    stat.symbol(), formatDecimal(stat.averageClose()), formatDecimal(stat.volatility()),
                    formatDecimal(stat.coefficientOfVariation()), formatDecimal(stat.beta()));
            }
        }
        List<CorrelationEntry> correlations = statistics.correlations();
        if (!correlations.isEmpty()) {
            out.println("Correlations (A vs B | covariance | correlation):");
            for (CorrelationEntry entry : correlations) {
                out.printf("%s vs %s | %s | %s%n",
                    entry.symbolA(), entry.symbolB(),
                    formatDecimal(entry.covariance()), formatDecimal(entry.correlation()));
            }
        }
    }

    static String formatDecimal(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    @CommandLine.Command(name = "portfolio", description = "Compute portfolio statistics")
    public static final class PortfolioStats implements Callable<Integer> {

        @CommandLine.ParentCommand
        AnalyticsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--portfolio-id", required = true)
        long portfolioId;

        @CommandLine.Option(names = "--lookback-days", defaultValue = "252")
        int lookbackDays;

        @CommandLine.Option(names = "--market", defaultValue = "SPY", description = "Benchmark symbol")
        String marketSymbol;

        @Override
        public Integer call() {
            PortfolioStatistics statistics = parent.analyticsService()
                .portfolioStatistics(parent.currentUserId(), portfolioId, lookbackDays, marketSymbol);
            PrintWriter out = spec.commandLine().getOut();
            renderStatistics(out, statistics);
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "list", description = "Compute statistics for a stock list")
    public static final class ListStats implements Callable<Integer> {

        @CommandLine.ParentCommand
        AnalyticsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--list-id", required = true)
        long listId;

        @CommandLine.Option(names = "--lookback-days", defaultValue = "252")
        int lookbackDays;

        @CommandLine.Option(names = "--market", defaultValue = "SPY", description = "Benchmark symbol")
        String marketSymbol;

        @Override
        public Integer call() {
            long userId = parent.currentUserId();
            StockListAccess access = parent.stockListService().findAccessibleList(userId, listId)
                .orElseThrow(() -> new CommandLine.ParameterException(spec.commandLine(), "List not accessible"));
            PrintWriter out = spec.commandLine().getOut();
            out.printf("List %d | %s | access %s%n", access.list().id(), access.list().name(), access.accessibility());
            PortfolioStatistics statistics = parent.analyticsService()
                .stockListStatistics(userId, listId, lookbackDays, marketSymbol);
            renderStatistics(out, statistics);
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "history", description = "Show historical closing prices for a symbol")
    public static final class History implements Callable<Integer> {

        @CommandLine.ParentCommand
        AnalyticsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--symbol", required = true)
        String symbol;

        @CommandLine.Option(names = "--lookback-days", defaultValue = "90")
        int lookbackDays;

        @Override
        public Integer call() {
            List<PriceHistoryPoint> history = parent.analyticsService().history(symbol, lookbackDays);
            PrintWriter out = spec.commandLine().getOut();
            if (history.isEmpty()) {
                out.printf("No history for %s%n", symbol.toUpperCase());
            } else {
                out.printf("History for %s:%n", symbol.toUpperCase());
                for (PriceHistoryPoint point : history) {
                    out.printf("- %s | close %s%n", point.day(), formatDecimal(point.close()));
                }
            }
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "predict", description = "Predict future prices for a symbol")
    public static final class Predict implements Callable<Integer> {

        @CommandLine.ParentCommand
        AnalyticsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--symbol", required = true)
        String symbol;

        @CommandLine.Option(names = "--horizon-days", defaultValue = "14")
        int horizonDays;

        @Override
        public Integer call() {
            List<PricePrediction> predictions;
            try {
                predictions = parent.analyticsService().predictLinear(symbol, horizonDays);
            } catch (IllegalArgumentException exception) {
                throw new CommandLine.ExecutionException(spec.commandLine(), exception.getMessage(), exception);
            }
            PrintWriter out = spec.commandLine().getOut();
            out.printf("Predictions for %s:%n", symbol.toUpperCase());
            for (PricePrediction prediction : predictions) {
                out.printf("- %s | predicted close %s%n", prediction.day(), formatDecimal(prediction.predictedClose()));
            }
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }
}
