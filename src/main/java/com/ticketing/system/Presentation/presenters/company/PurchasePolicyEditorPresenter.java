package com.ticketing.system.Presentation.presenters.company;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.Core.Application.dto.CompanyPolicyConfigDTO;
import com.ticketing.system.Core.Application.dto.EventDetailDTO;
import com.ticketing.system.Core.Application.dto.EventPolicyConfigDTO;
import com.ticketing.system.Core.Application.dto.MyCompanyDTO;
import com.ticketing.system.Core.Application.dto.PurchasePolicyDTO;
import com.ticketing.system.Core.Application.services.CompanyManagementService;
import com.ticketing.system.Core.Application.services.EventManagementService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.Presentation.session.SessionIdentity;

/**
 * MVP presenter for the purchase-policy editor.
 *
 * <p>Owns ALL policy logic and the editable model ({@link Node}/{@link Group}/{@link Rule}):
 * identity, context resolution (which companies/events the owner can edit), load/save,
 * DTO&lt;-&gt;model conversion, structural mutations, the D3 tree JSON, the plain-English
 * description, and validation. Stays stateless (like CheckoutPresenter) — the view holds a
 * single {@link Group} root as its UI state and only renders what the presenter returns.
 */
@Component
public class PurchasePolicyEditorPresenter {

    private final CompanyManagementService companyManagementService;
    private final EventManagementService   eventManagementService;
    private final SessionIdentity          identity;

    @Autowired
    public PurchasePolicyEditorPresenter(CompanyManagementService companyManagementService,
                                         EventManagementService eventManagementService,
                                         SessionIdentity identity) {
        this.companyManagementService = companyManagementService;
        this.eventManagementService   = eventManagementService;
        this.identity                 = identity;
    }


    public Identity resolveIdentity() {
        return new Identity(identity.memberToken());
    }

    public record Identity(String memberToken) { }

   

    public ContextOutcome resolveContext(String token) {
        if (token == null) return new ContextOutcome.NotAuthenticated();
        try {
            // Manager-inclusive: an EDIT_POLICIES manager (not just owners) must reach the editor.
            List<MyCompanyDTO> companies = companyManagementService.findMyCompanies(token);
            if (companies.isEmpty()) return new ContextOutcome.NoCompany();
            return new ContextOutcome.Ready(companies);
        } catch (InvalidTokenException e) {
            return new ContextOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new ContextOutcome.Failure(e.getMessage());
        }
    }

    /** Events belonging to a company; empty (never null) if the call fails. */
    public List<EventDetailDTO> listEvents(String token, int companyId) {
        if (token == null || companyId <= 0) return List.of();
        try {
            return eventManagementService.listEventsForCompany(token, companyId);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    public sealed interface ContextOutcome {
        record Ready(List<MyCompanyDTO> companies) implements ContextOutcome { }
        record NotAuthenticated() implements ContextOutcome { }
        record NoCompany() implements ContextOutcome { }
        record Failure(String reason) implements ContextOutcome { }
    }


    public enum Op { AND, OR }

    public enum RuleType {
        AgeAtLeast("years"),
        QuantityAtLeast("tickets"),
        QuantityAtMost("tickets");

        private final String unit;
        RuleType(String unit) { this.unit = unit; }
        public String unit() { return unit; }

        String prettyEnglish(String value) {
            return switch (this) {
                case AgeAtLeast      -> "Age at least " + value + " years";
                case QuantityAtLeast -> "Quantity at least " + value + " tickets";
                case QuantityAtMost  -> "Quantity at most "  + value + " tickets";
            };
        }
    }

    public sealed interface Node permits Group, Rule {
        String id();
    }

    public static final class Group implements Node {
        private final String id = newId();
        private Op op;
        private final List<Node> children = new ArrayList<>();
        private Group(Op op) { this.op = op; }
        public String id() { return id; }
        public Op op() { return op; }
        public List<Node> children() { return Collections.unmodifiableList(children); }
    }

    public static final class Rule implements Node {
        private final String id = newId();
        private RuleType type;
        private String value;
        private Rule(RuleType type, String value) { this.type = type; this.value = value; }
        public String id() { return id; }
        public RuleType type() { return type; }
        public String value() { return value; }
    }

    private static String newId() {
        return "n-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A fresh, empty policy (AND of nothing). Replaces the old hard-coded sample. */
    public Group emptyPolicy() {
        return new Group(Op.AND);
    }


    public void addRule(Group root, String parentId) {
        if (find(root, parentId) instanceof Group g) {
            g.children.add(new Rule(RuleType.AgeAtLeast, "18"));
        }
    }

    public void addGroup(Group root, String parentId) {
        if (find(root, parentId) instanceof Group g) {
            g.children.add(new Group(Op.AND));
        }
    }

    public void updateRule(Group root, String nodeId, RuleType type, String value) {
        if (find(root, nodeId) instanceof Rule r) {
            if (type != null) r.type = type;   // ignore null type — keeps r.type non-null for toTreeJson/nodeToDTO
            r.value = value == null ? "" : value.trim();
        }
    }

    public void updateGroupOp(Group root, String nodeId, Op op) {
        if (find(root, nodeId) instanceof Group g) {
            if (op != null) g.op = op;          // ignore null op — downstream assumes a non-null operator
        }
    }

    public void deleteNode(Group root, String nodeId) {
        Group parent = findParent(root, nodeId);
        if (parent != null) {
            parent.children.removeIf(child -> child.id().equals(nodeId));
        }
    }

    /** The root has no parent and must never be deletable. */
    public boolean canDelete(Group root, String nodeId) {
        return findParent(root, nodeId) != null;
    }


    public Node findNode(Group root, String nodeId) {
        return find(root, nodeId);
    }

    private Node find(Node node, String nodeId) {
        if (node.id().equals(nodeId)) return node;
        if (node instanceof Group g) {
            for (Node child : g.children) {
                Node hit = find(child, nodeId);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private Group findParent(Group root, String nodeId) {
        for (Node child : root.children) {
            if (child.id().equals(nodeId)) return root;
            if (child instanceof Group g) {
                Group hit = findParent(g, nodeId);
                if (hit != null) return hit;
            }
        }
        return null;
    }


    public String toTreeJson(Node n) {
        if (n instanceof Rule r) {
            return "{\"id\":\"" + r.id() + "\",\"kind\":\"rule\",\"label\":\""
                 + escapeJson(r.type.prettyEnglish(r.value == null ? "" : r.value)) + "\"}";
        }
        Group g = (Group) n;
        StringBuilder children = new StringBuilder();
        for (int i = 0; i < g.children.size(); i++) {
            if (i > 0) children.append(",");
            children.append(toTreeJson(g.children.get(i)));
        }
        String desc = g.op == Op.AND ? "all of these" : "any of these";
        return "{\"id\":\"" + g.id() + "\",\"kind\":\"comp\",\"op\":\""
             + g.op + "\",\"label\":\"" + escapeJson(g.op + " · " + desc)
             + "\",\"children\":[" + children + "]}";
    }

    public String toPlainEnglishHtml(Group root) {
        if (root.children.isEmpty()) {
            return "No restrictions yet — click the root group and add a rule to get started.";
        }
        return "Buyer must satisfy " + renderEnglish(root) + ".";
    }

    private String renderEnglish(Node node) {
        if (node instanceof Rule r) {
            return "<b>" + escape(r.type.prettyEnglish(r.value)) + "</b>";
        }
        Group g = (Group) node;
        if (g.children.isEmpty()) {
            return "<i>(empty " + g.op + " group)</i>";
        }
        String joiner = g.op == Op.AND ? "AND" : "OR";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.children.size(); i++) {
            if (i > 0) sb.append(" <span class='or'>").append(joiner).append("</span> ");
            Node ch = g.children.get(i);
            if (ch instanceof Group) sb.append("(").append(renderEnglish(ch)).append(")");
            else                     sb.append(renderEnglish(ch));
        }
        return sb.toString();
    }


    public Validity validate(Group root) {
        // An empty root means "no restrictions" — a valid NONE policy (see nodeToDTO).
        if (root.children.isEmpty()) {
            return Validity.OK;
        }
        return validateNode(root);
    }

    private Validity validateNode(Node node) {
        if (node instanceof Rule r) {
            if (r.value == null || r.value.isBlank()) {
                return Validity.invalid("A rule is missing its value.");
            }
            try {
                Integer.parseInt(r.value.trim());
            } catch (NumberFormatException e) {
                return Validity.invalid("Rule value must be a whole number.");
            }
            return Validity.OK;
        }
        Group g = (Group) node;
        if (g.children.isEmpty()) {
            return Validity.invalid("Empty " + g.op + " group — add at least one rule.");
        }
        for (Node child : g.children) {
            Validity cv = validateNode(child);
            if (!cv.valid()) return cv;
        }
        return Validity.OK;
    }

    public record Validity(boolean valid, String message) {
        static final Validity OK = new Validity(true, "Valid");
        static Validity invalid(String why) { return new Validity(false, why); }
    }


    public LoadOutcome load(String token, int companyId, int eventId, boolean isEventLevel) {
        if (token == null) return new LoadOutcome.NotAuthenticated();
        try {
            PurchasePolicyDTO dto = isEventLevel && eventId > 0
                ? eventManagementService.getEventPurchasePolicy(token, companyId, eventId)
                : companyManagementService.getCompanyPurchasePolicy(token, companyId);
            return new LoadOutcome.Success(dtoToRoot(dto));
        } catch (InvalidTokenException e) {
            return new LoadOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new LoadOutcome.Failure(e.getMessage());
        }
    }

    private Group dtoToRoot(PurchasePolicyDTO dto) {
        if (dto == null || "NONE".equals(dto.type())) {
            return emptyPolicy();
        }
        Node node = dtoToNode(dto);
        if (node instanceof Group g) return g;
        Group root = new Group(Op.AND);
        root.children.add(node);
        return root;
    }

    private Node dtoToNode(PurchasePolicyDTO dto) {
        return switch (dto.type()) {
            case "AGE"         -> new Rule(RuleType.AgeAtLeast,      String.valueOf(dto.minimumAge()));
            case "MIN_TICKETS" -> new Rule(RuleType.QuantityAtLeast, String.valueOf(dto.minimumTickets()));
            case "MAX_TICKETS" -> new Rule(RuleType.QuantityAtMost,  String.valueOf(dto.maximumTickets()));
            case "AND", "OR"   -> {
                Group g = new Group(Op.valueOf(dto.type()));
                if (dto.children() != null) {
                    for (PurchasePolicyDTO child : dto.children()) {
                        g.children.add(dtoToNode(child));
                    }
                }
                yield g;
            }
            default -> new Rule(RuleType.AgeAtLeast, "0");
        };
    }


    public SaveOutcome save(String token, int companyId, int eventId, boolean isEventLevel, Group root) {
        if (token == null) return new SaveOutcome.NotAuthenticated();
        Validity v = validate(root);
        if (!v.valid()) return new SaveOutcome.Invalid(v.message());
        try {
            PurchasePolicyDTO dto = nodeToDTO(root);
            if (isEventLevel) {
                eventManagementService.setEventPolicies(token,
                        new EventPolicyConfigDTO(companyId, eventId, dto));
            } else {
                companyManagementService.setCompanyPolicies(token,
                        new CompanyPolicyConfigDTO(companyId, dto, List.of()));
            }
            return new SaveOutcome.Success();
        } catch (InvalidTokenException e) {
            return new SaveOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new SaveOutcome.Failure(e.getMessage());
        }
    }

    private PurchasePolicyDTO nodeToDTO(Node node) {
        if (node instanceof Rule r) {
            int val = Integer.parseInt(r.value.trim()); // validate() guarantees this parses
            return switch (r.type) {
                case AgeAtLeast      -> new PurchasePolicyDTO("AGE",         val,  null, null, null);
                case QuantityAtLeast -> new PurchasePolicyDTO("MIN_TICKETS", null, val,  null, null);
                case QuantityAtMost  -> new PurchasePolicyDTO("MAX_TICKETS", null, null, val,  null);
            };
        }
        Group g = (Group) node;
        // An empty group is "no restrictions" — serialize to NONE rather than an invalid
        // 0-child AND/OR (the backend rejects composites with < 2 children).
        if (g.children.isEmpty()) {
            return new PurchasePolicyDTO("NONE", null, null, null, null);
        }
        List<PurchasePolicyDTO> children = g.children.stream().map(this::nodeToDTO).toList();
        // The backend requires AND/OR composites to carry >= 2 children; a single-child group
        // is semantically just its child, so unwrap it instead of emitting an invalid AND/OR.
        if (children.size() == 1) {
            return children.get(0);
        }
        String type = g.op == Op.AND ? "AND" : "OR";
        return new PurchasePolicyDTO(type, null, null, null, children);
    }


    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }


    public sealed interface LoadOutcome {
        record Success(Group root) implements LoadOutcome { }
        record NotAuthenticated() implements LoadOutcome { }
        record Failure(String reason) implements LoadOutcome { }
    }

    public sealed interface SaveOutcome {
        record Success() implements SaveOutcome { }
        record NotAuthenticated() implements SaveOutcome { }
        record Invalid(String reason) implements SaveOutcome { }
        record Failure(String reason) implements SaveOutcome { }
    }
}