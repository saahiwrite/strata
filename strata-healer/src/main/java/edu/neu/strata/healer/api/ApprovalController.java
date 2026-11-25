package edu.neu.strata.healer.api;

import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import edu.neu.strata.healer.orchestration.ApprovalService;
import edu.neu.strata.healer.orchestration.PendingApproval;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalService approvals;

    public ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    public List<PendingApproval> pending() {
        return approvals.pending();
    }

    @PostMapping("/{id}/approve")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void approve(@PathVariable long id, @RequestBody(required = false) Reviewer reviewer) {
        try {
            approvals.approve(id, nameOf(reviewer));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/{id}/deny")
    public void deny(@PathVariable long id, @RequestBody(required = false) Reviewer reviewer) {
        try {
            approvals.deny(id, nameOf(reviewer));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private static String nameOf(Reviewer reviewer) {
        return reviewer == null || reviewer.by() == null || reviewer.by().isBlank() ? "unknown" : reviewer.by().strip();
    }

    public record Reviewer(String by) {
    }
}
