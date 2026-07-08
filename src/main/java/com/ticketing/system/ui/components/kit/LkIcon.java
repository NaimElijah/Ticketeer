package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.html.Span;

import java.util.HashMap;
import java.util.Map;

/**
 * Inline SVG icon — ports the 43-glyph set from
 * {@code docs/tickethub-ui/lumo-kit.jsx} ({@code LK_ICONS}). All paths are
 * stroke-only, {@code currentColor}, 24×24 viewBox, drawn with a 1.7px
 * round-cap stroke. The glyph names match the React kit verbatim.
 *
 * <p>Renders the SVG by setting {@code innerHTML} on a wrapping
 * {@code <span class="lk-icon">} — the browser's HTML5 parser handles
 * the inline SVG namespace correctly. (Vaadin's {@code Html} component
 * uses Jsoup which loses the SVG namespace and silently drops the
 * paths.)
 */
public class LkIcon extends Span {

    private static final Map<String, String[]> PATHS = new HashMap<>();
    static {
        PATHS.put("ticket", new String[] {
                "M3 8.5A1.5 1.5 0 0 1 4.5 7H19.5A1.5 1.5 0 0 1 21 8.5v2a2 2 0 0 0 0 3.8v2A1.5 1.5 0 0 1 19.5 18H4.5A1.5 1.5 0 0 1 3 16.3v-2a2 2 0 0 0 0-3.8Z",
                "M14.5 7v11" });
        PATHS.put("building",
                new String[] { "M5 21V4.5A1.5 1.5 0 0 1 6.5 3h7A1.5 1.5 0 0 1 15 4.5V21",
                        "M15 9h3.5A1.5 1.5 0 0 1 20 10.5V21", "M3 21h18",
                        "M9 21v-3.5A1.5 1.5 0 0 1 10.5 16A1.5 1.5 0 0 1 12 17.5V21", "M8.4 7h0", "M8.4 11h0",
                        "M11.6 7h0", "M11.6 11h0" });
        PATHS.put("comment", new String[] {
                "M20.5 11.4a8 8 0 0 1-8.6 8 8.6 8.6 0 0 1-3.6-.86L3.5 20l1.46-4.8A8 8 0 0 1 4.5 11.4a8 8 0 0 1 8-8 8 8 0 0 1 8 8Z" });
        PATHS.put("flask", new String[] { "M9 3h6",
                "M10 3v6.4L4.8 18.3A1.6 1.6 0 0 0 6.2 21h11.6a1.6 1.6 0 0 0 1.4-2.7L14 9.4V3", "M7.3 15h9.4" });
        PATHS.put("info", new String[] { "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z", "M12 11.5v4.5", "M12 7.7h0" });
        PATHS.put("check", new String[] { "M20 6.5 9.5 17 4.5 12" });
        PATHS.put("close", new String[] { "M18 6 6 18", "M6 6l12 12" });
        PATHS.put("plus", new String[] { "M12 5.5v13", "M5.5 12h13" });
        PATHS.put("minus", new String[] { "M5.5 12h13" });
        PATHS.put("caret", new String[] { "M6 9l6 6 6-6" });
        PATHS.put("search", new String[] { "M10.5 18a7.5 7.5 0 1 0 0-15 7.5 7.5 0 0 0 0 15Z", "M21 21l-5.1-5.1" });
        PATHS.put("calendar", new String[] { "M5 5h14a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1Z",
                "M4 9.5h16", "M8 3v4", "M16 3v4" });
        PATHS.put("clock", new String[] { "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z", "M12 7.5V12l3 2" });
        PATHS.put("lock", new String[] { "M6 11h12a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1Z",
                "M8 11V8a4 4 0 0 1 8 0v3" });
        PATHS.put("card", new String[] { "M3 6h18a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1Z",
                "M2 10h20", "M6 15h4" });
        PATHS.put("cart", new String[] { "M3 4h2l2.1 11a1 1 0 0 0 1 .8h8.5a1 1 0 0 0 1-.8L20 7.5H6.2",
                "M9 21a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z", "M18 21a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z" });
        PATHS.put("bell", new String[] { "M18 9a6 6 0 1 0-12 0c0 5-2 6-2 6h16s-2-1-2-6Z", "M10.4 20a2 2 0 0 0 3.2 0" });
        PATHS.put("users", new String[] { "M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z", "M3 20a6 6 0 0 1 12 0",
                "M16 4.5a3.5 3.5 0 0 1 0 7", "M17 14.2a6 6 0 0 1 4 5.8" });
        PATHS.put("crown", new String[] { "M3 8l4 3 5-6 5 6 4-3-1.6 11H4.6L3 8Z", "M5 19h14" });
        PATHS.put("briefcase", new String[] { "M4 8h16a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1Z",
                "M9 8V6a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2", "M3 13h18" });
        PATHS.put("chart", new String[] { "M4 20V4", "M4 20h16", "M8 20v-6", "M13 20v-9", "M18 20v-4" });
        PATHS.put("map", new String[] { "M9 4 4 6v14l5-2 6 2 5-2V4l-5 2-6-2Z", "M9 4v14", "M15 6v14" });
        PATHS.put("seat", new String[] { "M5 12V7a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v5",
                "M4 12a2 2 0 0 1 2 2v3h12v-3a2 2 0 0 1 2-2", "M6 17v2", "M18 17v2" });
        PATHS.put("mobile",
                new String[] { "M7 3h10a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z", "M11 18h2" });
        PATHS.put("menu", new String[] { "M4 7h16", "M4 12h16", "M4 17h16" });
        PATHS.put("warning", new String[] { "M12 4.5l8.5 14.5a1 1 0 0 1-.9 1.5H4.4a1 1 0 0 1-.9-1.5L12 4.5Z",
                "M12 10v4", "M12 17.6h0" });
        PATHS.put("edit", new String[] { "M5 19h4l9.4-9.4a1.5 1.5 0 0 0 0-2.1l-1.9-1.9a1.5 1.5 0 0 0-2.1 0L5 15v4Z",
                "M13.5 6.5l4 4" });
        PATHS.put("policy", new String[] { "M7 3h7l4 4v13a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z", "M14 3v4h4",
                "M9 12h6", "M9 16h5", "M9 8h2" });
        PATHS.put("org", new String[] { "M9 3h6v4H9z", "M3 17h6v4H3z", "M15 17h6v4h-6z", "M12 7v3",
                "M6 17v-2a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v2" });
        PATHS.put("music", new String[] { "M9 18V5l11-2v13", "M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z",
                "M20 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z" });
        PATHS.put("mic", new String[] { "M12 2a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z",
                "M5 10v1a7 7 0 0 0 14 0v-1", "M12 18v4", "M8 22h8" });
        PATHS.put("trophy", new String[] { "M8 4h8v5a4 4 0 0 1-8 0V4Z", "M8 5H5v1.5a3 3 0 0 0 3 3",
                "M16 5h3v1.5a3 3 0 0 1-3 3", "M12 13v4", "M8.5 21h7", "M10 21a2 2 0 0 1 4 0" });
        PATHS.put("theater", new String[] { "M4 4h16v4H4z", "M6 8v8c2.6 0 4-2.6 4-8", "M18 8v8c-2.6 0-4-2.6-4-8" });
        PATHS.put("star",
                new String[] { "M12 3.5l2.6 5.3 5.8.9-4.2 4.1 1 5.8-5.2-2.8-5.2 2.8 1-5.8L3.6 9.7l5.8-.9L12 3.5Z" });
        PATHS.put("crosshair",
                new String[] { "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z", "M12 2v4", "M12 18v4", "M2 12h4", "M18 12h4" });
        PATHS.put("checkCircle", new String[] { "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z", "M8.4 12l2.5 2.5 4.7-5.2" });
        PATHS.put("arrowRight", new String[] { "M5 12h14", "M13 6l6 6-6 6" });
        PATHS.put("arrowLeft", new String[] { "M19 12H5", "M11 6l-6 6 6 6" });
        PATHS.put("gear", new String[] { "M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z",
                "M19.4 13a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-2.7 1.1V20a2 2 0 1 1-4 0v-.2a1.6 1.6 0 0 0-2.7-1.1l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1A1.6 1.6 0 0 0 4 13H3.8a2 2 0 1 1 0-4H4a1.6 1.6 0 0 0 1.1-2.7l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1A1.6 1.6 0 0 0 11 4V3.8a2 2 0 1 1 4 0V4a1.6 1.6 0 0 0 2.7 1.1l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1A1.6 1.6 0 0 0 20 11h.2a2 2 0 1 1 0 4H20a1.6 1.6 0 0 0-1.5 1Z" });
        PATHS.put("logout", new String[] { "M9 21H5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h4", "M16 17l5-5-5-5", "M21 12H9" });
        PATHS.put("more", new String[] { "M12 6h0", "M12 12h0", "M12 18h0" });
        PATHS.put("copy", new String[] { "M9 9h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V10a1 1 0 0 1 1-1Z",
                "M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1" });
        PATHS.put("trash", new String[] { "M4 7h16", "M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2",
                "M6 7l1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13", "M10 11v6", "M14 11v6" });
        PATHS.put("eye", new String[] { "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z",
                "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z" });
    }

    public LkIcon(String name) {
        this(name, 18, 1.7);
    }

    public LkIcon(String name, int size) {
        this(name, size, 1.7);
    }

    public LkIcon(String name, int size, double stroke) {
        addClassName("lk-icon");
        getStyle()
            .set("display", "inline-flex")
            .set("align-items", "center")
            .set("justify-content", "center")
            .set("flex-shrink", "0")
            .set("line-height", "0");
        getElement().setProperty("innerHTML", buildSvg(name, size, stroke));
    }

    private static String buildSvg(String name, int size, double stroke) {
        StringBuilder svg = new StringBuilder(256);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"")
            .append(size).append("\" height=\"").append(size)
            .append("\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"")
            .append(stroke)
            .append("\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">");
        String[] paths = PATHS.getOrDefault(name, new String[0]);
        for (String p : paths) svg.append("<path d=\"").append(p).append("\"/>");
        svg.append("</svg>");
        return svg.toString();
    }
}
