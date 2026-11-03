package me.nageshk.omnichannel.app.automation;

import java.util.List;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.automation.AutomationTestService;
import me.nageshk.omnichannel.service.automation.PreflightView;
import me.nageshk.omnichannel.service.automation.TestContactView;
import me.nageshk.omnichannel.service.automation.TestStartRequest;
import me.nageshk.omnichannel.service.automation.TestView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Step-by-step tests of a brand's draft rules. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/automations")
class AutomationTestController {

    private final AutomationTestService tests;

    AutomationTestController(AutomationTestService tests) {
        this.tests = tests;
    }

    @GetMapping("/test-contacts")
    @PreAuthorize("@access.has(#workspaceId, 'automation:manage')")
    List<TestContactView> contacts(@PathVariable String workspaceId, @RequestParam(required = false) String q) {
        return tests.contacts(workspaceId, q);
    }

    @PostMapping("/rules/{id}/tests")
    @PreAuthorize("@access.has(#workspaceId, 'automation:manage')")
    ResponseEntity<TestView> start(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody TestStartRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tests.start(me, workspaceId, id, req));
    }

    @GetMapping("/rules/{id}/tests/active")
    @PreAuthorize("@access.has(#workspaceId, 'automation:manage')")
    ResponseEntity<TestView> active(@PathVariable String workspaceId, @PathVariable String id) {
        return tests.active(workspaceId, id).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/rules/{id}/tests/preflight")
    @PreAuthorize("@access.has(#workspaceId, 'automation:manage')")
    PreflightView preflight(@PathVariable String workspaceId, @PathVariable String id) {
        return tests.preflight(workspaceId, id);
    }

    @GetMapping("/tests/{testId}")
    @PreAuthorize("@access.has(#workspaceId, 'automation:manage')")
    TestView get(@PathVariable String workspaceId, @PathVariable String testId) {
        return tests.get(workspaceId, testId);
    }

    @PostMapping("/tests/{testId}/check")
    @PreAuthorize("@access.has(#workspaceId, 'automation:manage')")
    TestView check(@PathVariable String workspaceId, @PathVariable String testId) {
        return tests.check(workspaceId, testId);
    }

    @PostMapping("/tests/{testId}/actions/{index}/run")
    @PreAuthorize("@access.has(#workspaceId, 'automation:manage')")
    TestView run(@PathVariable String workspaceId, @PathVariable String testId, @PathVariable int index) {
        return tests.runAction(workspaceId, testId, index);
    }

    @PostMapping("/tests/{testId}/actions/{index}/skip")
    @PreAuthorize("@access.has(#workspaceId, 'automation:manage')")
    TestView skip(@PathVariable String workspaceId, @PathVariable String testId, @PathVariable int index) {
        return tests.skipAction(workspaceId, testId, index);
    }

    @PostMapping("/tests/{testId}/again")
    @PreAuthorize("@access.has(#workspaceId, 'automation:manage')")
    TestView again(@PathVariable String workspaceId, @PathVariable String testId) {
        return tests.again(workspaceId, testId);
    }

    @PostMapping("/tests/{testId}/replay")
    @PreAuthorize("@access.has(#workspaceId, 'automation:manage')")
    TestView replay(@PathVariable String workspaceId, @PathVariable String testId) {
        return tests.replay(workspaceId, testId);
    }

    @PostMapping("/tests/{testId}/end")
    @PreAuthorize("@access.has(#workspaceId, 'automation:manage')")
    TestView end(@PathVariable String workspaceId, @PathVariable String testId) {
        return tests.end(workspaceId, testId);
    }
}
