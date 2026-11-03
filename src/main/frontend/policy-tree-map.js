// Policy tree renderer — D3.js node-link diagram with pan/zoom + inline
// add-rule / add-group affordances on every composite node.
//
// Loaded by Vaadin via @JsModule on PolicyTreeMap.java. Exposes
// `window.renderPolicyTree(container, treeJson)` which the Vaadin
// wrapper calls via Element.executeJs whenever the tree mutates.
//
// User actions come back through `container.$server.onNodeAction(id, action)`
// where action is "click" (opened the edit dialog), "addRule", or
// "addGroup". The Vaadin side resolves the id to a Java Node and
// mutates / opens dialogs as appropriate.
//
// Every node body and pill is a keyboard button (asKeyboardButton): Tab reaches it, a screen
// reader names it, and Enter or Space runs the same action as a click. A re-render puts focus
// back on the control that had it, or, when focus has left the tree, as an edit dialog takes it,
// on the control last activated. A control taking focus outside the visible part of the panned
// or zoomed tree pans it into view (#188; WCAG 2.1.1, 2.4.3, 2.4.11, 4.1.2; PolicyEditorA11yTest).

const D3_URL = 'https://cdn.jsdelivr.net/npm/d3@7/+esm';

let d3Promise = null;
function getD3() {
    if (!d3Promise) {
        d3Promise = import(/* @vite-ignore */ D3_URL);
    }
    return d3Promise;
}

// Names the control a re-render must give focus back to.
const FOCUS_KEY = 'data-focus-key';
// How near the svg's edge, in its viewBox units, a focused control may sit before the tree pans to it.
const VIEW_MARGIN = 40;
// Per tree container, the control whose action ran last. An edit dialog takes focus out of the tree and
// its Save re-renders it, so the re-render finds no focused control of its own to give focus back to.
const lastActivated = new WeakMap();

function focusKey(nodeId, action) {
    return `${nodeId}:${action}`;
}

function runNodeAction(container, nodeId, action) {
    lastActivated.set(container, focusKey(nodeId, action));
    if (container.$server) {
        container.$server.onNodeAction(nodeId, action);
    }
}

// Makes an svg group a keyboard button that runs `action` on node `nodeId`, as a click does. `onFocus`
// runs when it takes keyboard focus.
function asKeyboardButton(sel, container, nodeId, action, name, onFocus) {
    sel.attr('role', 'button')
        .attr('tabindex', 0)
        .attr('aria-label', name)
        .attr(FOCUS_KEY, focusKey(nodeId, action))
        .on('focus', (ev) => {
            // Focus is back in the tree, so a later re-render must not pull it to an older action's control.
            lastActivated.delete(container);
            // Keyboard focus only: a pan under a mouse press would move the control before the click lands.
            if (ev.currentTarget.matches(':focus-visible')) {
                onFocus();
            }
        })
        .on('click', (ev) => {
            ev.stopPropagation();
            runNodeAction(container, nodeId, action);
        })
        .on('keydown', (ev) => {
            if (ev.key === 'Enter' || ev.key === ' ') {
                // Space would otherwise scroll the page.
                ev.preventDefault();
                ev.stopPropagation();
                runNodeAction(container, nodeId, action);
            }
        });
}

window.renderPolicyTree = async function(container, treeJson) {
    try {
        const d3 = await getD3();
        const data = JSON.parse(treeJson);

        // The tree is redrawn from scratch, so remember which control had focus, or, with focus outside
        // the tree, the one last activated. Taken once: a later re-render must not pull focus back again.
        const focused = container.contains(document.activeElement)
            ? document.activeElement.getAttribute(FOCUS_KEY) : lastActivated.get(container);
        lastActivated.delete(container);

        container.innerHTML = '';

        const width  = container.clientWidth  || 800;
        const height = container.clientHeight || 520;

        const svg = d3.select(container)
            .append('svg')
            .attr('width',  '100%')
            .attr('height', '100%')
            .attr('viewBox', `0 0 ${width} ${height}`)
            .attr('role', 'group')
            .attr('aria-label', 'Purchase policy tree')
            .style('display', 'block')
            .style('user-select', 'none');

        const g = svg.append('g');

        // Generous node sizes so siblings + add-button pills never overlap.
        // [horizontal-between-siblings, vertical-between-levels]
        const root = d3.hierarchy(data);
        const treeLayout = d3.tree().nodeSize([260, 150]);
        treeLayout(root);

        // Centre the tree horizontally based on its bounds.
        let minX = Infinity, maxX = -Infinity;
        root.each(d => {
            if (d.x < minX) minX = d.x;
            if (d.x > maxX) maxX = d.x;
        });
        const treeWidth = maxX - minX;
        const initialX  = (width - treeWidth) / 2 - minX;
        const initialY  = 80;

        const zoomBehavior = d3.zoom()
            .scaleExtent([0.3, 3])
            .on('zoom', ev => g.attr('transform', ev.transform));

        svg.call(zoomBehavior)
           .call(zoomBehavior.transform,
                 d3.zoomIdentity.translate(initialX, initialY));

        // Pans the tree so the point (x, y) of the drawing is in view, if the current pan and zoom have put it
        // near or past the svg's edge: a keyboard user cannot see a focused control off screen (WCAG 2.4.11).
        const keepInView = (x, y) => {
            const [sx, sy] = d3.zoomTransform(svg.node()).apply([x, y]);
            if (sx < VIEW_MARGIN || sx > width - VIEW_MARGIN || sy < VIEW_MARGIN || sy > height - VIEW_MARGIN) {
                zoomBehavior.translateTo(svg, x, y);
            }
        };

        // Connector lines (curved Bezier).
        g.selectAll('path.link')
            .data(root.links())
            .join('path')
            .attr('class', 'link')
            .attr('fill',   'none')
            .attr('stroke', '#94a3b8')
            .attr('stroke-width', 1.8)
            .attr('d', d3.linkVertical()
                .x(d => d.x)
                .y(d => d.y));

        // Nodes.
        const nodes = g.selectAll('g.node')
            .data(root.descendants())
            .join('g')
            .attr('class', 'node')
            .attr('transform', d => `translate(${d.x}, ${d.y})`);

        nodes.each(function(d) {
            const sel    = d3.select(this);
            const isRule = d.data.kind === 'rule';
            const isNot  = d.data.op   === 'NOT';

            const fill   = isRule ? '#dcfce7' : (isNot ? '#fee2e2' : '#fef3c7');
            const stroke = isRule ? '#15803d' : (isNot ? '#b91c1c' : '#d97706');
            const fg     = isRule ? '#14532d' : (isNot ? '#7f1d1d' : '#92400e');

            const label  = d.data.label || '';
            const labelW = Math.max(90, label.length * 7 + 30);

            // Body group — the clickable rect+text. Has its own group so the
            // add-buttons on composites don't inherit the body's click handler.
            const body = sel.append('g')
                .attr('class', 'node-body')
                .style('cursor', 'pointer');
            asKeyboardButton(body, container, d.data.id, 'click', `Edit ${label}`, () => keepInView(d.x, d.y));

            body.append('rect')
                .attr('x', -labelW / 2)
                .attr('y', -20)
                .attr('width',  labelW)
                .attr('height', 40)
                .attr('rx', 9)
                .attr('fill',   fill)
                .attr('stroke', stroke)
                .attr('stroke-width', 1.5);

            body.append('text')
                .attr('text-anchor', 'middle')
                .attr('dy', '0.35em')
                .style('font-size',   '12.5px')
                .style('font-weight', '700')
                .style('font-family', 'inherit')
                .style('fill', fg)
                .text(label);

            // Composite nodes get inline +Rule / +Group pills to the right.
            if (!isRule) {
                const pillX = labelW / 2 + 22;
                addPill(sel, container, d.data.id, label, 'addRule', '+ R', 'Add rule',
                    pillX, -14, '#dcfce7', '#15803d', '#14532d', () => keepInView(d.x + pillX, d.y - 14));
                addPill(sel, container, d.data.id, label, 'addGroup', '+ G', 'Add group',
                    pillX, 14, '#fef3c7', '#d97706', '#92400e', () => keepInView(d.x + pillX, d.y + 14));
            }
        });

        // Pan/zoom hint pill in the bottom-right corner.
        const hint = svg.append('g')
            .attr('transform', `translate(${width - 200}, ${height - 28})`);
        hint.append('rect')
            .attr('width', 192).attr('height', 22)
            .attr('rx', 11)
            .attr('fill', 'rgba(15,23,42,0.7)');
        hint.append('text')
            .attr('x', 96).attr('y', 15)
            .attr('text-anchor', 'middle')
            .style('font-size', '10.5px')
            .style('font-family', 'inherit')
            .style('font-weight', '600')
            .style('fill', '#fff')
            .text('Drag to pan · scroll to zoom');

        if (focused) {
            const again = container.querySelector(`[${FOCUS_KEY}="${CSS.escape(focused)}"]`);
            if (again) {
                again.focus();
            }
        }
    } catch (e) {
        console.error('PolicyTreeMap failed:', e);
        container.innerHTML =
            '<div style="padding:24px;color:#dc2626;font-family:sans-serif">'
            + 'Tree library failed to load. Check your network and reload.'
            + '</div>';
    }
};

// Pill button (rounded rect + label) used for the inline +Rule / +Group
// affordances on composite nodes. Its name says which node it adds to, as every
// composite has a pair. `onFocus` runs when it takes keyboard focus.
function addPill(sel, container, nodeId, nodeLabel, action, label, title,
                 dx, dy, fill, stroke, fg, onFocus) {
    const w = 38, h = 22;
    const btn = sel.append('g')
        .attr('class', 'add-pill')
        .attr('transform', `translate(${dx}, ${dy})`)
        .style('cursor', 'pointer');
    asKeyboardButton(btn, container, nodeId, action, `${title} to ${nodeLabel}`, onFocus);
    btn.append('title').text(title);
    btn.append('rect')
        .attr('x', -w / 2).attr('y', -h / 2)
        .attr('width', w).attr('height', h)
        .attr('rx', 11)
        .attr('fill',   fill)
        .attr('stroke', stroke)
        .attr('stroke-dasharray', '3 3')
        .attr('stroke-width', 1.3);
    btn.append('text')
        .attr('text-anchor', 'middle')
        .attr('dy', '0.35em')
        .style('font-size', '10.5px')
        .style('font-weight', '800')
        .style('font-family', 'inherit')
        .style('letter-spacing', '0.04em')
        .style('fill', fg)
        .text(label);
}
