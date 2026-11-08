package com.ticketing.system.ui.components.admin;

import com.ticketing.system.organization.application.dto.OrganizationalTreeNodeDTO;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.ui.support.DisplayLocale;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;

import java.util.stream.Collectors;

public class OrgTreeRenderer extends Composite<Div> {

    private static final String ROLE = "role";
    private static final String ROLE_LIST = "list";
    private static final String ARIA_HIDDEN = "aria-hidden";

    public OrgTreeRenderer(OrganizationalTreeNodeDTO root) {
        Div tree = getContent();
        tree.addClassName("org-chart");
        UnorderedList ul = list();
        ul.add(buildNode(root));
        tree.add(ul);
    }

    private ListItem buildNode(OrganizationalTreeNodeDTO dto) {
        ListItem li = new ListItem();

        boolean isOwner  = "Owner".equals(dto.role());
        String variant   = dto.isFounder() ? "founder" : isOwner ? "owner" : "manager";
        String roleLabel = dto.isFounder() ? "Founder" : isOwner ? "Owner"  : "Manager";
        String initial   = dto.username().substring(0, 1).toUpperCase(DisplayLocale.PAGE);
        boolean isManager = !dto.isFounder() && !isOwner;
        String sub = dto.isFounder() ? "Company Founder"
                : isManager ? dto.grantedPermissions().stream()
                        .map(Permission::name)
                        .collect(Collectors.joining(", "))
                : "";

        boolean composite = !dto.appointedByThisUser().isEmpty();

        Div ocCard = new Div();
        ocCard.addClassName("oc-card");
        ocCard.addClassName("oc-" + variant);

        Span kind = new Span(composite ? "Composite" : "Leaf");
        kind.addClassName("oc-kind");
        kind.addClassName("lk-mono");

        Span avatar = new Span(initial);
        avatar.addClassName("oc-avatar");
        avatar.addClassName("oc-av-" + variant);
        // The initial repeats the name beside it (#188, WCAG 1.1.1).
        avatar.getElement().setAttribute(ARIA_HIDDEN, "true");

        Div name = new Div(new Span(dto.username()));
        name.addClassName("oc-name");

        Span role = new Span(roleLabel);
        role.addClassName("oc-role");
        role.addClassName("oc-rb-" + variant);

        ocCard.add(kind, avatar, name, role);
        if (!sub.isEmpty()) {
            Div subDiv = new Div(new Span(sub));
            subDiv.addClassName("oc-sub");
            ocCard.add(subDiv);
        }
        li.add(ocCard);

        if (composite) {
            UnorderedList kidsUl = list();
            for (OrganizationalTreeNodeDTO child : dto.appointedByThisUser()) kidsUl.add(buildNode(child));
            li.add(kidsUl);
        }
        return li;
    }

    // The chart's styling drops the bullets, and Safari then stops calling the list a list; the role keeps
    // the nesting, and how many each member appointed, audible (#188, WCAG 1.3.1).
    private static UnorderedList list() {
        UnorderedList ul = new UnorderedList();
        ul.getElement().setAttribute(ROLE, ROLE_LIST);
        return ul;
    }
}
