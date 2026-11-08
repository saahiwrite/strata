package edu.neu.strata.healer.api;

import edu.neu.strata.healer.trust.TrustRecord;
import edu.neu.strata.healer.trust.TrustService;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/trust}: every action type's success record (FR10, FR12). */
@RestController
public class TrustController {

    private final TrustService trust;

    public TrustController(TrustService trust) {
        this.trust = Objects.requireNonNull(trust, "trust");
    }

    @GetMapping("/api/trust")
    public List<TrustRecord> list() {
        return trust.all();
    }
}
