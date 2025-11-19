package cscc43.cli;

import cscc43.auth.AuthContext;
import cscc43.model.Review;
import cscc43.model.User;
import cscc43.service.ReviewService;
import cscc43.service.UserService;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Commands for writing and managing stock list reviews.
 */
@CommandLine.Command(name = "reviews", description = "Review stock lists", subcommands = {
    ReviewsCommand.ListReviews.class,
    ReviewsCommand.UpsertReview.class,
    ReviewsCommand.DeleteReview.class
})
public final class ReviewsCommand implements Runnable {

    private final AuthContext authContext;
    private final ReviewService reviewService;
    private final UserService userService;

    public ReviewsCommand(AuthContext authContext, ReviewService reviewService, UserService userService) {
        this.authContext = authContext;
        this.reviewService = reviewService;
        this.userService = userService;
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    long currentUserId() {
        return authContext.requireUser().id();
    }

    ReviewService reviewService() {
        return reviewService;
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

    @CommandLine.Command(name = "list", description = "Show reviews for an accessible list")
    public static final class ListReviews implements Callable<Integer> {

        @CommandLine.ParentCommand
        ReviewsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--list-id", required = true)
        long listId;

        @Override
        public Integer call() {
            long userId = parent.currentUserId();
            List<Review> reviews = parent.reviewService().listReviews(userId, listId);
            PrintWriter out = spec.commandLine().getOut();
            if (reviews.isEmpty()) {
                out.println("No reviews found");
            } else {
                out.printf("Reviews for list %d:%n", listId);
                for (Review review : reviews) {
                    out.printf("- by %s | created %s", parent.describeUser(review.reviewerId()), review.createdAt());
                    if (review.updatedAt() != null) {
                        out.printf(" | updated %s", review.updatedAt());
                    }
                    out.println();
                    out.println("  " + review.body());
                }
            }
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "upsert", description = "Create or update your review")
    public static final class UpsertReview implements Callable<Integer> {

        @CommandLine.ParentCommand
        ReviewsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--list-id", required = true)
        long listId;

        @CommandLine.Option(names = "--body", required = true, description = "Review body (<= 4000 characters)")
        String body;

        @Override
        public Integer call() {
            long userId = parent.currentUserId();
            Review review = parent.reviewService().upsertReview(userId, listId, body);
            PrintWriter out = spec.commandLine().getOut();
            out.printf("Saved review for list %d by %s%n", listId, parent.describeUser(review.reviewerId()));
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "delete", description = "Delete a review you own or a review on your list")
    public static final class DeleteReview implements Callable<Integer> {

        @CommandLine.ParentCommand
        ReviewsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--list-id", required = true)
        long listId;

        @CommandLine.Option(names = "--reviewer-id", description = "Reviewer id (defaults to you)")
        Long reviewerId;

        @Override
        public Integer call() {
            long actor = parent.currentUserId();
            long reviewer = reviewerId == null ? actor : reviewerId;
            parent.reviewService().deleteReview(actor, listId, reviewer);
            PrintWriter out = spec.commandLine().getOut();
            out.printf("Deleted review by %s on list %d%n", parent.describeUser(reviewer), listId);
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }
}
