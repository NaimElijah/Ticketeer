package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Static data grid — ports the React {@code Grid} component. Renders an
 * HTML {@code <table>} with the kit's {@code .lk-grid} styling.
 *
 * <pre>{@code
 * LkGrid grid = new LkGrid()
 *         .col("Event", "name")
 *         .col("Date", "date")
 *         .col("Total", "total", Align.RIGHT)
 *         .row(Map.of("name", "Coldplay · MOTS", "date", "26 Jun", "total", "$504"));
 * }</pre>
 */
public class LkGrid extends Div {

    public enum Align {
        LEFT, RIGHT, CENTER;

        public String css() {
            return name().toLowerCase();
        }
    }

    public static class Col {
        public final String header;
        public final String key;
        public final Align align;
        public final String width;

        public Col(String header, String key, Align align, String width) {
            this.header = header;
            this.key = key;
            this.align = align;
            this.width = width;
        }
    }

    private final List<Col> columns = new ArrayList<>();
    private final List<Object> rows = new ArrayList<>();
    private boolean dense;

    public LkGrid() {
        addClassName("lk-grid-wrap");
    }

    public LkGrid dense() {
        dense = true;
        return this;
    }

    public LkGrid col(String header, String key) {
        return col(header, key, Align.LEFT, null);
    }

    public LkGrid col(String header, String key, Align align) {
        return col(header, key, align, null);
    }

    public LkGrid col(String header, String key, Align align, String width) {
        columns.add(new Col(header, key, align, width));
        return this;
    }

    /**
     * Add a row of cell values keyed by {@link Col#key}. Values may be
     * {@code String} or {@link Component}.
     */
    public LkGrid row(Map<String, Object> cells) {
        rows.add(cells);
        return this;
    }

    /** Render the table. Call once columns + rows are configured. */
    public LkGrid build() {
        removeAll();
        Element tbl = new Element("table");
        tbl.setAttribute("class", "lk-grid" + (dense ? " dense" : ""));

        Element thead = new Element("thead");
        Element thr = new Element("tr");
        for (Col c : columns) {
            Element th = new Element("th");
            th.setText(c.header == null ? "" : c.header);
            StringBuilder s = new StringBuilder();
            if (c.align != null && c.align != Align.LEFT)
                s.append("text-align:").append(c.align.css()).append(';');
            if (c.width != null)
                s.append("width:").append(c.width).append(';');
            if (s.length() > 0)
                th.setAttribute("style", s.toString());
            thr.appendChild(th);
        }
        thead.appendChild(thr);
        tbl.appendChild(thead);

        Element tbody = new Element("tbody");
        for (Object rowObj : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) rowObj;
            Element tr = new Element("tr");
            for (Col c : columns) {
                Element td = new Element("td");
                if (c.align != null && c.align != Align.LEFT)
                    td.setAttribute("style", "text-align:" + c.align.css());
                Object cell = row.get(c.key);
                if (cell == null)
                    td.setText("");
                else if (cell instanceof Component cmp)
                    td.appendChild(cmp.getElement());
                else
                    td.setText(cell.toString());
                tr.appendChild(td);
            }
            tbody.appendChild(tr);
        }
        tbl.appendChild(tbody);

        Div wrap = new Div();
        wrap.getElement().appendChild(tbl);
        add(wrap);
        return this;
    }
}
