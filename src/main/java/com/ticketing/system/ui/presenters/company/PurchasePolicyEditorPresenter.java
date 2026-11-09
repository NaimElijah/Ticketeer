package com.ticketing.system.ui.presenters.company;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.CompanyPolicyConfigDTO;
import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.shared.dto.EventPolicyConfigDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.shared.dto.PurchasePolicyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * MVP presenter for the purchase-policy editor.
 *
 * <p>Owns ALL policy logic and the editable model ({@link Node}/{@link Group}/{@link Rule}/{@link KeptRule}):
 * identity, context resolution (which companies/events the owner can edit), load/save,
 * DTO&lt;-&gt;model conversion, structural mutations, the D3 tree JSON, the plain-English
 * description, and validation. Holds no per-user state: the view holds a single {@link Group} root as
 * its UI state and only renders what the presenter returns.
 */
@Component
public class PurchasePolicyEditorPresenter {

    /** U+0020, the space: every character below it is a control character JSON must escape. */
    private static final char FIRST_NON_CONTROL_CHAR = ' ';

    private final CompanyManagementService companyManagementService;
    private final EventManagementService   eventManagementService;
    private final SessionIdentity          identity;

    /**
     * Creates the presenter.
     *
     * @param companyManagementService lists the caller's companies and reads and writes company policies
     * @param eventManagementService   lists a company's events and reads and writes event policies
     * @param identity                 resolves the live member, if any
     */
    @Autowired
    public PurchasePolicyEditorPresenter(CompanyManagementService companyManagementService,
                                         EventManagementService eventManagementService,
                                         SessionIdentity identity) {
        this.companyManagementService = companyManagementService;
        this.eventManagementService   = eventManagementService;
        this.identity                 = identity;
    }


    /** The visitor as the editor needs them: the live member's token, if there is one. */
    public Identity resolveIdentity() {
        return new Identity(identity.memberToken());
    }

    /**
     * The visitor, as {@link #resolveIdentity()} resolved it.
     *
     * @param memberToken the live member's token, or {@code null} for a guest or an admin
     */
    public record Identity(String memberToken) { }



    /**
     * Finds the companies whose policy the caller can open in the editor.
     *
     * @param token the caller's member token; {@code null} gives {@link ContextOutcome.NotAuthenticated}
     * @return {@link ContextOutcome.Ready} with the caller's companies, managers' included, since the
     *     service decides per call whether a manager may edit policies; {@link ContextOutcome.NoCompany}
     *     when the caller belongs to none; {@link ContextOutcome.Failure} with {@code ServiceErrors}'
     *     text for any other failure
     */
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
            return new ContextOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /**
     * Events belonging to a company; none without a token or a company, and a
     * {@link ListOutcome.Failure} with {@code ServiceErrors}' text when the read fails.
     */
    public ListOutcome<EventDetailDTO> listEvents(String token, int companyId) {
        if (token == null || companyId <= 0) return new ListOutcome.Loaded<>(List.of());
        try {
            return new ListOutcome.Loaded<>(eventManagementService.listEventsForCompany(token, companyId));
        } catch (RuntimeException e) {
            return new ListOutcome.Failure<>(ServiceErrors.userMessage(e));
        }
    }

    /** Sealed outcome of {@link #resolveContext}, which the view switches on. */
    public sealed interface ContextOutcome {
        /**
         * The caller has companies to edit.
         *
         * @param companies the caller's companies
         */
        record Ready(List<MyCompanyDTO> companies) implements ContextOutcome { }

        /** There is no token, or the service rejected it as invalid. */
        record NotAuthenticated() implements ContextOutcome { }

        /** The caller belongs to no company. */
        record NoCompany() implements ContextOutcome { }

        /**
         * The read failed.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements ContextOutcome { }
    }


    /** How a {@link Group} combines its children: all of them must hold, or any one of them. */
    public enum Op { AND, OR }

    /** The kinds of rule the editor can build, each with the unit its value is counted in. */
    public enum RuleType {
        AgeAtLeast("years"),
        QuantityAtLeast("tickets"),
        QuantityAtMost("tickets");

        private final String unit;
        RuleType(String unit) { this.unit = unit; }
        /** The unit the rule's value is counted in: {@code years} or {@code tickets}. */
        public String unit() { return unit; }

        String prettyEnglish(String value) {
            return switch (this) {
                case AgeAtLeast      -> "Age at least " + value + " years";
                case QuantityAtLeast -> "Quantity at least " + value + " tickets";
                case QuantityAtMost  -> "Quantity at most "  + value + " tickets";
            };
        }
    }

    /** One node of the editable policy tree. */
    public sealed interface Node permits Group, Rule, KeptRule {
        /** The node's id, generated at random when the node is built; the view passes it back to name the node. */
        String id();
    }

    /** A node that combines its children with an {@link Op}. The tree's root is always a group. */
    public static final class Group implements Node {
        private final String id = newId();
        private Op op;
        private final List<Node> children = new ArrayList<>();
        private Group(Op op) { this.op = op; }
        public String id() { return id; }
        public Op op() { return op; }
        /** The group's children in order, as a read-only view; the presenter's methods change them. */
        public List<Node> children() { return Collections.unmodifiableList(children); }
    }

    /** A leaf the editor can edit: a {@link RuleType} and the value it is compared with. */
    public static final class Rule implements Node {
        private final String id = newId();
        private RuleType type;
        private String value;
        private Rule(RuleType type, String value) { this.type = type; this.value = value; }
        public String id() { return id; }
        public RuleType type() { return type; }
        public String value() { return value; }
    }

    /**
     * A stored rule the editor cannot represent, such as a kind the backend gained after the editor was
     * written, or a {@code NONE} nested in a group. It cannot be edited, and it is saved back exactly as
     * it was loaded: reading it as one of the editor's own rule types would change the policy on save.
     */
    public static final class KeptRule implements Node {
        private final String id = newId();
        private final PurchasePolicyDTO stored;
        private KeptRule(PurchasePolicyDTO stored) { this.stored = stored; }
        public String id() { return id; }
        /** The rule's kind as stored, for display. */
        public String kind() { return stored.type(); }
    }

    private static String newId() {
        return "n-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A fresh, empty policy (AND of nothing). */
    public Group emptyPolicy() {
        return new Group(Op.AND);
    }


    /**
     * Adds a rule, "age at least 18" to start with, as the last child of a group. Does nothing when
     * {@code parentId} names no group of the tree.
     *
     * @param root     the tree
     * @param parentId the id of the group to add to
     */
    public void addRule(Group root, String parentId) {
        if (find(root, parentId) instanceof Group g) {
            g.children.add(new Rule(RuleType.AgeAtLeast, "18"));
        }
    }

    /**
     * Adds an empty {@code AND} group as the last child of a group. Does nothing when {@code parentId}
     * names no group of the tree.
     *
     * @param root     the tree
     * @param parentId the id of the group to add to
     */
    public void addGroup(Group root, String parentId) {
        if (find(root, parentId) instanceof Group g) {
            g.children.add(new Group(Op.AND));
        }
    }

    /**
     * Changes a rule's type and value. Does nothing when {@code nodeId} names no rule of the tree.
     *
     * @param root   the tree
     * @param nodeId the id of the rule to change
     * @param type   the new type; {@code null} keeps the current one
     * @param value  the new value as typed, kept trimmed; {@code null} becomes an empty string
     */
    public void updateRule(Group root, String nodeId, RuleType type, String value) {
        if (find(root, nodeId) instanceof Rule r) {
            if (type != null) r.type = type;   // ignore null type — keeps r.type non-null for toTreeJson/nodeToDTO
            r.value = value == null ? "" : value.trim();
        }
    }

    /**
     * Changes how a group combines its children. Does nothing when {@code nodeId} names no group of
     * the tree.
     *
     * @param root   the tree
     * @param nodeId the id of the group to change
     * @param op     the new operator; {@code null} keeps the current one
     */
    public void updateGroupOp(Group root, String nodeId, Op op) {
        if (find(root, nodeId) instanceof Group g) {
            if (op != null) g.op = op;          // ignore null op — downstream assumes a non-null operator
        }
    }

    /**
     * Removes a node, with its descendants, from its parent. Does nothing for the root and for an id
     * the tree does not hold.
     *
     * @param root   the tree
     * @param nodeId the id of the node to remove
     */
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


    /**
     * Finds a node by id.
     *
     * @param root   the tree
     * @param nodeId the id to look for
     * @return the node, the root included, or {@code null} when the tree holds no such id
     */
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


    /**
     * Renders a node and its descendants as the JSON the tree diagram reads. Every node has an
     * {@code id}, a {@code kind} ({@code rule} or {@code comp}) and a {@code label}; a group also has
     * its {@code op} and its {@code children}. Text inside a label is escaped for JSON.
     *
     * @param n the node to render, usually the root
     * @return the JSON text
     */
    public String toTreeJson(Node n) {
        if (n instanceof Rule r) {
            return "{\"id\":\"" + r.id() + "\",\"kind\":\"rule\",\"label\":\""
                 + escapeJson(r.type.prettyEnglish(r.value == null ? "" : r.value)) + "\"}";
        }
        if (n instanceof KeptRule k) {
            return "{\"id\":\"" + k.id() + "\",\"kind\":\"rule\",\"label\":\"" + escapeJson(keptLabel(k)) + "\"}";
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

    /**
     * Describes the policy in a sentence for the page. The result is HTML: the user's text in it is
     * escaped, and the markup is only {@code b}, {@code i} and {@code span} tags.
     *
     * @param root the tree
     * @return the sentence; a hint to add a rule when the root has no children
     */
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
        if (node instanceof KeptRule k) {
            return "<b>" + escape(keptLabel(k)) + "</b>";
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


    /**
     * Checks that the tree can be saved. An empty root is valid and means no restrictions. Otherwise
     * every rule needs a whole-number value and every group at least one child.
     *
     * @param root the tree
     * @return a valid {@link Validity}, or the first problem found, in a message the user may read
     */
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
        if (node instanceof KeptRule) {
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

    /**
     * The result of {@link #validate}.
     *
     * @param valid   whether the tree can be saved
     * @param message for a problem, what is wrong, in text the user may read
     */
    public record Validity(boolean valid, String message) {
        static final Validity OK = new Validity(true, "Valid");
        static Validity invalid(String why) { return new Validity(false, why); }
    }


    /**
     * Loads a stored policy into an editable tree. A policy of type {@code NONE}, or none stored, gives
     * an empty tree.
     *
     * @param token        the caller's member token; {@code null} gives {@link LoadOutcome.NotAuthenticated}
     * @param companyId    the company whose policy to load
     * @param eventId      the event whose policy to load; used only when {@code isEventLevel} is set
     *     and the id is positive, else the company's policy is loaded
     * @param isEventLevel {@code true} to load the event's own policy
     * @return {@link LoadOutcome.Success} with the tree; {@link LoadOutcome.Failure} with
     *     {@code ServiceErrors}' text when the service refuses or fails
     */
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
            return new LoadOutcome.Failure(ServiceErrors.userMessage(e));
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
            default -> new KeptRule(dto);
        };
    }


    /**
     * Validates the tree and stores it as the company's or the event's policy. A group with a single
     * child is stored as that child, and an empty root as {@code NONE}, since the backend refuses a
     * composite with fewer than two children.
     *
     * @param token        the caller's member token; {@code null} gives {@link SaveOutcome.NotAuthenticated}
     * @param companyId    the company the policy belongs to
     * @param eventId      the event, when {@code isEventLevel} is set
     * @param isEventLevel {@code true} to store the event's own policy, {@code false} the company's
     * @param root         the tree to store
     * @return {@link SaveOutcome.Invalid} when {@link #validate} refuses the tree, without calling the
     *     service; {@link SaveOutcome.Failure} with {@code ServiceErrors}' text when the service
     *     refuses or fails
     */
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
            return new SaveOutcome.Failure(ServiceErrors.userMessage(e));
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
        if (node instanceof KeptRule k) {
            return k.stored;
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


    private static String keptLabel(KeptRule k) {
        return "Unsupported rule (" + k.kind() + "), kept as it is";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // JSON, and so the browser's JSON.parse, rejects a raw character below U+0020 inside a string, and a
    // rule's label carries whatever the user typed.
    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c == '\\' || c == '"') {
                escaped.append('\\').append(c);
            } else if (c < FIRST_NON_CONTROL_CHAR) {
                escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }


    /** Sealed outcome of {@link #load}, which the view switches on. */
    public sealed interface LoadOutcome {
        /**
         * The policy loaded.
         *
         * @param root the policy as an editable tree
         */
        record Success(Group root) implements LoadOutcome { }

        /** There is no token, or the service rejected it as invalid. */
        record NotAuthenticated() implements LoadOutcome { }

        /**
         * The service refused the read or failed.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements LoadOutcome { }
    }

    /** Sealed outcome of {@link #save}, which the view switches on. */
    public sealed interface SaveOutcome {
        /** The service stored the policy. */
        record Success() implements SaveOutcome { }

        /** There is no token, or the service rejected it as invalid. */
        record NotAuthenticated() implements SaveOutcome { }

        /**
         * The tree failed {@link #validate}, so the service was not called.
         *
         * @param reason what is wrong, in text the user may read
         */
        record Invalid(String reason) implements SaveOutcome { }

        /**
         * The service refused the policy or failed.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements SaveOutcome { }
    }
}