package cscc43.cli;

import cscc43.auth.AuthContext;
import cscc43.model.StockList;
import cscc43.model.StockListAccess;
import cscc43.model.StockListItem;
import cscc43.model.StockListVisibility;
import cscc43.model.User;
import cscc43.service.StockListService;
import cscc43.service.UserService;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Commands for managing stock lists and their sharing settings.
 */
@CommandLine.Command(name = "lists", description = "Manage stock lists", subcommands = {
    ListsCommand.Mine.class,
    ListsCommand.Accessible.class,
    ListsCommand.Create.class,
    ListsCommand.UpdateVisibility.class,
    ListsCommand.Delete.class,
    ListsCommand.AddItem.class,
    ListsCommand.RemoveItem.class,
    ListsCommand.Items.class,
    ListsCommand.Share.class,
    ListsCommand.Unshare.class,
    ListsCommand.SharedWith.class
})
public final class ListsCommand implements Runnable {

    private final AuthContext authContext;
    private final StockListService stockListService;
    private final UserService userService;

    public ListsCommand(AuthContext authContext, StockListService stockListService, UserService userService) {
        this.authContext = authContext;
        this.stockListService = stockListService;
        this.userService = userService;
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    long currentUserId() {
        return authContext.requireUser().id();
    }

    StockListService stockListService() {
        return stockListService;
    }

    String describeUser(long userId) {
        Optional<User> user = userService.findById(userId);
        if (user.isPresent()) {
            User value = user.get();
            String name = value.name() == null || value.name().isBlank() ? "(no name)" : value.name();
            return String.format("%s <%s> [id %d]", name, value.email(), value.id());
        }
        return "user id " + userId;
    }

    static String describeList(StockList list) {
        return String.format("%d | %s | visibility %s | owner %d | created %s",
            list.id(), list.name(), list.visibility(), list.ownerId(), list.createdAt());
    }

    @CommandLine.Command(name = "mine", description = "List stock lists you own")
    public static final class Mine implements Callable<Integer> {

        @CommandLine.ParentCommand
        ListsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            List<StockList> lists = parent.stockListService().ownerLists(parent.currentUserId());
            PrintWriter out = spec.commandLine().getOut();
            if (lists.isEmpty()) {
                out.println("You have not created any lists");
            } else {
                out.println("Your lists:");
                for (StockList list : lists) {
                    out.printf("- %s%n", describeList(list));
                }
            }
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "accessible", description = "List stock lists you can view")
    public static final class Accessible implements Callable<Integer> {

        @CommandLine.ParentCommand
        ListsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            List<StockListAccess> lists = parent.stockListService().accessibleLists(parent.currentUserId());
            PrintWriter out = spec.commandLine().getOut();
            if (lists.isEmpty()) {
                out.println("No accessible lists");
            } else {
                out.println("Accessible lists:");
                for (StockListAccess access : lists) {
                    StockList list = access.list();
                    out.printf("- %s | access %s%n", describeList(list), access.accessibility());
                }
            }
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "create", description = "Create a stock list")
    public static final class Create implements Callable<Integer> {

        @CommandLine.ParentCommand
        ListsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--name", required = true)
        String name;

        @CommandLine.Option(names = "--visibility", defaultValue = "private")
        StockListVisibility visibility;

        @Override
        public Integer call() {
            StockList list = parent.stockListService().createList(parent.currentUserId(), name, visibility);
            PrintWriter out = spec.commandLine().getOut();
            out.printf("Created list %s%n", describeList(list));
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "update-visibility", description = "Change a list's visibility")
    public static final class UpdateVisibility implements Callable<Integer> {

        @CommandLine.ParentCommand
        ListsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--list-id", required = true)
        long listId;

        @CommandLine.Option(names = "--visibility", required = true)
        StockListVisibility visibility;

        @Override
        public Integer call() {
            StockList updated = parent.stockListService().updateVisibility(parent.currentUserId(), listId, visibility);
            PrintWriter out = spec.commandLine().getOut();
            out.printf("Visibility updated: %s%n", describeList(updated));
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "delete", description = "Delete one of your stock lists")
    public static final class Delete implements Callable<Integer> {

        @CommandLine.ParentCommand
        ListsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--list-id", required = true)
        long listId;

        @Override
        public Integer call() {
            parent.stockListService().deleteList(parent.currentUserId(), listId);
            PrintWriter out = spec.commandLine().getOut();
            out.printf("Deleted list %d%n", listId);
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "add-item", description = "Add or replace a symbol in a list")
    public static final class AddItem implements Callable<Integer> {

        @CommandLine.ParentCommand
        ListsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--list-id", required = true)
        long listId;

        @CommandLine.Option(names = "--symbol", required = true)
        String symbol;

        @CommandLine.Option(names = "--shares", required = true)
        BigDecimal shares;

        @CommandLine.Option(names = "--company-name", description = "Optional company name for the symbol")
        String companyName;

        @Override
        public Integer call() throws Exception {
            try {
                parent.stockListService().addItem(parent.currentUserId(), listId, symbol, shares, companyName);
            } catch (SQLException exception) {
                throw new CommandLine.ExecutionException(spec.commandLine(), "Failed to add symbol to list", exception);
            }
            PrintWriter out = spec.commandLine().getOut();
            out.printf("Added %s (shares %s) to list %d%n", symbol.toUpperCase(), shares, listId);
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "remove-item", description = "Remove a symbol from a list")
    public static final class RemoveItem implements Callable<Integer> {

        @CommandLine.ParentCommand
        ListsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--list-id", required = true)
        long listId;

        @CommandLine.Option(names = "--symbol", required = true)
        String symbol;

        @Override
        public Integer call() {
            parent.stockListService().removeItem(parent.currentUserId(), listId, symbol);
            PrintWriter out = spec.commandLine().getOut();
            out.printf("Removed %s from list %d%n", symbol.toUpperCase(), listId);
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "items", description = "Show the contents of a list")
    public static final class Items implements Callable<Integer> {

        @CommandLine.ParentCommand
        ListsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--list-id", required = true)
        long listId;

        @Override
        public Integer call() {
            long userId = parent.currentUserId();
            StockListAccess access = parent.stockListService().findAccessibleList(userId, listId)
                .orElseThrow(() -> new CommandLine.ParameterException(spec.commandLine(), "List not accessible"));
            List<StockListItem> items = parent.stockListService().listItems(listId);
            PrintWriter out = spec.commandLine().getOut();
            out.printf("List: %s%n", describeList(access.list()));
            if (items.isEmpty()) {
                out.println("No symbols recorded");
            } else {
                out.println("Symbols:");
                for (StockListItem item : items) {
                    out.printf("- %s | shares %s%n", item.symbol(), item.shares());
                }
            }
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "share", description = "Share a list with a friend")
    public static final class Share implements Callable<Integer> {

        @CommandLine.ParentCommand
        ListsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--list-id", required = true)
        long listId;

        @CommandLine.Option(names = "--user-id", required = true)
        long userId;

        @Override
        public Integer call() {
            parent.stockListService().shareWithFriend(parent.currentUserId(), listId, userId);
            PrintWriter out = spec.commandLine().getOut();
            out.printf("Shared list %d with %s%n", listId, parent.describeUser(userId));
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "unshare", description = "Remove a friend share")
    public static final class Unshare implements Callable<Integer> {

        @CommandLine.ParentCommand
        ListsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--list-id", required = true)
        long listId;

        @CommandLine.Option(names = "--user-id", required = true)
        long userId;

        @Override
        public Integer call() {
            parent.stockListService().unshareWithFriend(parent.currentUserId(), listId, userId);
            PrintWriter out = spec.commandLine().getOut();
            out.printf("Stopped sharing list %d with %s%n", listId, parent.describeUser(userId));
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "shared-with", description = "Show friend ids a list is shared with")
    public static final class SharedWith implements Callable<Integer> {

        @CommandLine.ParentCommand
        ListsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--list-id", required = true)
        long listId;

        @Override
        public Integer call() {
            List<Long> users = parent.stockListService().sharedWith(parent.currentUserId(), listId);
            PrintWriter out = spec.commandLine().getOut();
            if (users.isEmpty()) {
                out.println("List is not shared with any friends");
            } else {
                out.println("Shared with:");
                for (Long userId : users) {
                    out.printf("- %s%n", parent.describeUser(userId));
                }
            }
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }
}
