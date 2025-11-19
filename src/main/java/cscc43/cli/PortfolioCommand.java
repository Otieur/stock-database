package cscc43.cli;

import cscc43.auth.AuthContext;
import cscc43.model.Portfolio;
import cscc43.model.PortfolioPosition;
import cscc43.model.PortfolioSummary;
import cscc43.model.TransactionKind;
import cscc43.service.PortfolioService;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Portfolio-oriented commands for the CLI.
 */
@CommandLine.Command(name = "portfolios", description = "Manage portfolios", subcommands = {
    PortfolioCommand.ListPortfolios.class,
    PortfolioCommand.CreatePortfolio.class,
    PortfolioCommand.DepositCash.class,
    PortfolioCommand.WithdrawCash.class,
    PortfolioCommand.BuyStock.class,
    PortfolioCommand.SellStock.class,
    PortfolioCommand.Summary.class,
    PortfolioCommand.Holdings.class
})
public final class PortfolioCommand implements Runnable {

    private final AuthContext authContext;
    private final PortfolioService portfolioService;

    public PortfolioCommand(AuthContext authContext, PortfolioService portfolioService) {
        this.authContext = authContext;
        this.portfolioService = portfolioService;
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    long currentUserId() {
        return authContext.requireUser().id();
    }

    PortfolioService portfolioService() {
        return portfolioService;
    }

    @CommandLine.Command(name = "list", description = "List portfolios for the current user")
    public static final class ListPortfolios implements Callable<Integer> {

        @CommandLine.ParentCommand
        PortfolioCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            List<Portfolio> portfolios = parent.portfolioService().listPortfolios(parent.currentUserId());
            PrintWriter out = spec.commandLine().getOut();
            if (portfolios.isEmpty()) {
                out.println("No portfolios found");
            } else {
                out.println("Portfolios:");
                for (Portfolio portfolio : portfolios) {
                    out.printf("- %d | %s | cash %s | created %s%n",
                        portfolio.id(), portfolio.name(), portfolio.cashBalance(), portfolio.createdAt());
                }
            }
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "create", description = "Create a new portfolio")
    public static final class CreatePortfolio implements Callable<Integer> {

        @CommandLine.ParentCommand
        PortfolioCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--name", required = true)
        String name;

        @Override
        public Integer call() {
            Portfolio portfolio = parent.portfolioService().createPortfolio(parent.currentUserId(), name);
            PrintWriter out = spec.commandLine().getOut();
            out.printf("Created portfolio %s with id %d%n", portfolio.name(), portfolio.id());
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "deposit", description = "Deposit cash into a portfolio")
    public static final class DepositCash implements Callable<Integer> {

        @CommandLine.ParentCommand
        PortfolioCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--portfolio-id", required = true)
        long portfolioId;

        @CommandLine.Option(names = "--amount", required = true)
        BigDecimal amount;

        @CommandLine.Option(names = "--kind", description = "Transaction kind (default DEPOSIT)")
        TransactionKind kind = TransactionKind.DEPOSIT;

        @Override
        public Integer call() {
            Portfolio portfolio = parent.portfolioService().deposit(parent.currentUserId(), portfolioId, amount, kind);
            PrintWriter out = spec.commandLine().getOut();
            out.printf("New cash balance: %s%n", portfolio.cashBalance());
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "withdraw", description = "Withdraw cash from a portfolio")
    public static final class WithdrawCash implements Callable<Integer> {

        @CommandLine.ParentCommand
        PortfolioCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--portfolio-id", required = true)
        long portfolioId;

        @CommandLine.Option(names = "--amount", required = true)
        BigDecimal amount;

        @CommandLine.Option(names = "--kind", description = "Transaction kind (default WITHDRAW)")
        TransactionKind kind = TransactionKind.WITHDRAW;

        @Override
        public Integer call() {
            Portfolio portfolio = parent.portfolioService().withdraw(parent.currentUserId(), portfolioId, amount, kind);
            PrintWriter out = spec.commandLine().getOut();
            out.printf("New cash balance: %s%n", portfolio.cashBalance());
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "buy", description = "Record a stock purchase")
    public static final class BuyStock implements Callable<Integer> {

        @CommandLine.ParentCommand
        PortfolioCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--portfolio-id", required = true)
        long portfolioId;

        @CommandLine.Option(names = "--symbol", required = true)
        String symbol;

        @CommandLine.Option(names = "--shares", required = true)
        BigDecimal shares;

        @CommandLine.Option(names = "--price", required = true)
        BigDecimal price;

        @Override
        public Integer call() throws Exception {
            try {
                Portfolio portfolio = parent.portfolioService().buyStock(parent.currentUserId(), portfolioId, symbol, shares, price);
                PrintWriter out = spec.commandLine().getOut();
                out.printf("Trade recorded. Cash balance: %s%n", portfolio.cashBalance());
                out.flush();
                return CommandLine.ExitCode.OK;
            } catch (SQLException exception) {
                throw new CommandLine.ExecutionException(spec.commandLine(), "Failed to record trade", exception);
            }
        }
    }

    @CommandLine.Command(name = "sell", description = "Record a stock sale")
    public static final class SellStock implements Callable<Integer> {

        @CommandLine.ParentCommand
        PortfolioCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--portfolio-id", required = true)
        long portfolioId;

        @CommandLine.Option(names = "--symbol", required = true)
        String symbol;

        @CommandLine.Option(names = "--shares", required = true)
        BigDecimal shares;

        @CommandLine.Option(names = "--price", required = true)
        BigDecimal price;

        @Override
        public Integer call() throws Exception {
            try {
                Portfolio portfolio = parent.portfolioService().sellStock(parent.currentUserId(), portfolioId, symbol, shares, price);
                PrintWriter out = spec.commandLine().getOut();
                out.printf("Trade recorded. Cash balance: %s%n", portfolio.cashBalance());
                out.flush();
                return CommandLine.ExitCode.OK;
            } catch (SQLException exception) {
                throw new CommandLine.ExecutionException(spec.commandLine(), "Failed to record trade", exception);
            }
        }
    }

    @CommandLine.Command(name = "summary", description = "Show portfolio snapshot")
    public static final class Summary implements Callable<Integer> {

        @CommandLine.ParentCommand
        PortfolioCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--portfolio-id", required = true)
        long portfolioId;

        @Override
        public Integer call() {
            PortfolioSummary summary = parent.portfolioService().summary(parent.currentUserId(), portfolioId);
            PrintWriter out = spec.commandLine().getOut();
            out.printf("Portfolio %s | Cash %s | Total %s%n",
                summary.portfolio().name(), summary.portfolio().cashBalance(), summary.totalValue());
            out.println("Holdings:");
            for (PortfolioPosition position : summary.positions()) {
                Optional<BigDecimal> lastClose = position.lastClose();
                Optional<BigDecimal> marketValue = position.marketValue();
                out.printf("- %s | shares %s", position.symbol(), position.shares());
                lastClose.ifPresent(price -> out.printf(" | last close %s", price));
                marketValue.ifPresent(value -> out.printf(" | market value %s", value));
                out.println();
            }
            out.printf("Holdings market value: %s%n", summary.holdingsMarketValue());
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "holdings", description = "List holdings without valuation")
    public static final class Holdings implements Callable<Integer> {

        @CommandLine.ParentCommand
        PortfolioCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--portfolio-id", required = true)
        long portfolioId;

        @Override
        public Integer call() {
            List<PortfolioPosition> holdings = parent.portfolioService().holdings(parent.currentUserId(), portfolioId);
            PrintWriter out = spec.commandLine().getOut();
            if (holdings.isEmpty()) {
                out.println("No holdings recorded");
            } else {
                out.println("Holdings:");
                for (PortfolioPosition position : holdings) {
                    out.printf("- %s | shares %s%n", position.symbol(), position.shares());
                }
            }
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }
}
