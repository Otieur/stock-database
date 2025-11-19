package cscc43.cli;

import cscc43.auth.AuthContext;
import cscc43.model.FriendRequest;
import cscc43.model.Friendship;
import cscc43.model.User;
import cscc43.service.FriendService;
import cscc43.service.UserService;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * CLI entry points for friend management workflows.
 */
@CommandLine.Command(name = "friends", description = "Manage friend requests and friendships", subcommands = {
    FriendsCommand.ListFriends.class,
    FriendsCommand.Incoming.class,
    FriendsCommand.Outgoing.class,
    FriendsCommand.SendRequest.class,
    FriendsCommand.Respond.class,
    FriendsCommand.Cancel.class,
    FriendsCommand.Remove.class
})
public final class FriendsCommand implements Runnable {

    private final AuthContext authContext;
    private final FriendService friendService;
    private final UserService userService;

    public FriendsCommand(AuthContext authContext, FriendService friendService, UserService userService) {
        this.authContext = authContext;
        this.friendService = friendService;
        this.userService = userService;
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    long currentUserId() {
        return authContext.requireUser().id();
    }

    FriendService friendService() {
        return friendService;
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

    @CommandLine.Command(name = "list", description = "List accepted friends")
    public static final class ListFriends implements Callable<Integer> {

        @CommandLine.ParentCommand
        FriendsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            long userId = parent.currentUserId();
            List<Friendship> friendships = parent.friendService().friendships(userId);
            PrintWriter out = spec.commandLine().getOut();
            if (friendships.isEmpty()) {
                out.println("No friends yet");
            } else {
                out.println("Friends:");
                for (Friendship friendship : friendships) {
                    long other = friendship.other(userId);
                    out.printf("- %s | since %s%n", parent.describeUser(other), friendship.sinceAt());
                }
            }
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "incoming", description = "Show pending friend requests you need to respond to")
    public static final class Incoming implements Callable<Integer> {

        @CommandLine.ParentCommand
        FriendsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            long userId = parent.currentUserId();
            List<FriendRequest> requests = parent.friendService().incomingRequests(userId);
            PrintWriter out = spec.commandLine().getOut();
            if (requests.isEmpty()) {
                out.println("No incoming requests");
            } else {
                out.println("Incoming requests:");
                for (FriendRequest request : requests) {
                    out.printf("- request %d from %s | status %s | created %s%n",
                        request.id(), parent.describeUser(request.requesterId()),
                        request.status(), request.createdAt());
                }
            }
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "outgoing", description = "Show friend requests you have sent")
    public static final class Outgoing implements Callable<Integer> {

        @CommandLine.ParentCommand
        FriendsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            long userId = parent.currentUserId();
            List<FriendRequest> requests = parent.friendService().outgoingRequests(userId);
            PrintWriter out = spec.commandLine().getOut();
            if (requests.isEmpty()) {
                out.println("No outgoing requests");
            } else {
                out.println("Outgoing requests:");
                for (FriendRequest request : requests) {
                    out.printf("- request %d to %s | status %s | created %s%n",
                        request.id(), parent.describeUser(request.receiverId()),
                        request.status(), request.createdAt());
                }
            }
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "request", description = "Send a friend request")
    public static final class SendRequest implements Callable<Integer> {

        @CommandLine.ParentCommand
        FriendsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--user-id", required = true, description = "Recipient user id")
        long userId;

        @Override
        public Integer call() {
            long requester = parent.currentUserId();
            FriendRequest request = parent.friendService().sendRequest(requester, userId);
            PrintWriter out = spec.commandLine().getOut();
            out.printf("Request %d created and pending for %s%n", request.id(), parent.describeUser(userId));
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "respond", description = "Accept or reject a pending friend request")
    public static final class Respond implements Callable<Integer> {

        enum Action {
            ACCEPT,
            REJECT
        }

        @CommandLine.ParentCommand
        FriendsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--request-id", required = true)
        long requestId;

        @CommandLine.Option(names = "--action", required = true)
        Action action;

        @Override
        public Integer call() {
            parent.friendService().respondToRequest(parent.currentUserId(), requestId, action == Action.ACCEPT);
            PrintWriter out = spec.commandLine().getOut();
            out.printf("Request %d marked as %s%n", requestId, action.name().toLowerCase());
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "cancel", description = "Cancel a pending friend request you sent")
    public static final class Cancel implements Callable<Integer> {

        @CommandLine.ParentCommand
        FriendsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--request-id", required = true)
        long requestId;

        @Override
        public Integer call() {
            parent.friendService().cancelRequest(parent.currentUserId(), requestId);
            PrintWriter out = spec.commandLine().getOut();
            out.printf("Request %d cancelled%n", requestId);
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "remove", description = "Remove an existing friend")
    public static final class Remove implements Callable<Integer> {

        @CommandLine.ParentCommand
        FriendsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--user-id", required = true)
        long friendId;

        @Override
        public Integer call() {
            parent.friendService().removeFriend(parent.currentUserId(), friendId);
            PrintWriter out = spec.commandLine().getOut();
            out.printf("Removed friendship with %s%n", parent.describeUser(friendId));
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }
}
