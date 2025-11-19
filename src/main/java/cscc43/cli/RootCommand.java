package cscc43.cli;

import cscc43.auth.AuthContext;
import cscc43.service.PortfolioService;
import cscc43.service.UserService;
import picocli.CommandLine;

/**
 * Root command for the interactive CLI shell.
 */
@CommandLine.Command(name = "cscc43", description = "CSCC43 portfolio manager CLI", mixinStandardHelpOptions = true)
public final class RootCommand implements Runnable {

    final AuthContext authContext;
    final UserService userService;
    final PortfolioService portfolioService;

    public RootCommand(AuthContext authContext, UserService userService, PortfolioService portfolioService) {
        this.authContext = authContext;
        this.userService = userService;
        this.portfolioService = portfolioService;
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
