package com.ticketing.system.ui.components.policy;

import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.html.Div;

import java.util.function.BiConsumer;

/**
 * Vaadin wrapper around a D3.js node-link tree.
 *
 * <p>{@code policy-tree-map.js} (loaded via {@link JsModule}) registers
 * a global {@code window.renderPolicyTree(container, treeJson)} function.
 * Calling {@link #render(String)} pushes a fresh JSON model into the
 * client; the D3 layer rebuilds the SVG (pan/zoom via {@code d3-zoom},
 * layout via {@code d3-hierarchy.tree}).
 *
 * <p>The JS layer dispatches three actions through one
 * {@code @ClientCallable}: {@code "click"} (clicked the node body —
 * open editor), {@code "addRule"} / {@code "addGroup"} (clicked the
 * inline +R / +G affordances next to a composite). The registered
 * {@link BiConsumer} receives {@code (nodeId, action)} pairs.
 *
 * <p>The pending-JSON pattern handles the case where {@link #render} is
 * called before the component is attached to a UI — the first attach
 * pushes the buffered tree.
 */
@JsModule("./policy-tree-map.js")
public class PolicyTreeMap extends Div {

    private BiConsumer<String, String> onAction;
    private String pendingJson;

    public PolicyTreeMap() {
        addClassName("policy-tree-map");
        getStyle()
            .set("width",  "100%")
            .set("height", "560px")
            .set("background", "#f5f8fc")
            .set("background-image", "radial-gradient(#d6dfeb 1.2px, transparent 1.2px)")
            .set("background-size", "20px 20px")
            .set("border", "1px solid var(--border)")
            .set("border-radius", "14px")
            .set("overflow", "hidden")
            .set("position", "relative");

        addAttachListener(e -> {
            if (pendingJson != null) {
                callRender(pendingJson);
            }
        });
    }

    /** Push a fresh JSON tree to the client and re-render. */
    public void render(String treeJson) {
        if (isAttached()) {
            callRender(treeJson);
        } else {
            pendingJson = treeJson;
        }
    }

    private void callRender(String treeJson) {
        getElement().executeJs("window.renderPolicyTree(this, $0);", treeJson);
        pendingJson = null;
    }

    /** Register a handler that receives {@code (nodeId, action)} pairs. */
    public void setOnAction(BiConsumer<String, String> handler) {
        this.onAction = handler;
    }

    /** Called from {@code policy-tree-map.js} on every node interaction. */
    @ClientCallable
    private void onNodeAction(String nodeId, String action) {
        if (onAction != null) onAction.accept(nodeId, action);
    }
}
