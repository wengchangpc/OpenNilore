package client.nilore.gui.nilore;

/**
 * Geometry of the Nilore click GUI.
 *
 * <p>Row heights and type metrics are fixed; the three panes are re-flowed by {@link #resize} so a
 * window can be dragged to a different size without touching the type scale.
 */
public final class NiloreLayout {
    public static final float NATURAL_W = 1360, NATURAL_H = 860;
    public static final float MIN_W = 900, MIN_H = 560, MAX_W = 1920, MAX_H = 1080;

    public static float WIDTH = NATURAL_W, HEIGHT = NATURAL_H;

    public static final float SIDEBAR_W = 250;
    public static float LIST_W = 750, DETAIL_W = 360;
    public static float LIST_X = SIDEBAR_W, DETAIL_X = SIDEBAR_W + LIST_W;

    public static Rect WINDOW = new Rect(0, 0, WIDTH, HEIGHT);

    public static final float RADIUS = NiloreTheme.Radius.WINDOW;
    public static final float TITLEBAR_H = NiloreTheme.Metrics.TITLEBAR_H;
    public static final float CONTENT_TOP = TITLEBAR_H;

    /**
     * Window scale relative to the fitted size.
     *
     * <p>0.8 reproduces the reference (apple.html: {@code width: min(1360px, 70vw)}, {@code height:
     * min(860px, 78vh)}): at 1920x1080 with GUI scale 2 the fit factor is .609, so the panel lands
     * at .487 and covers 1326x838 physical px - 69% of the width and 78% of the height, the same
     * fraction as the reference. Everything inside, type included, then scales as one block.
     */
    public static final float WINDOW_SCALE = .8f;

    /**
     * Line heights from the reference stylesheet. Text blocks are stacked with line boxes the way a
     * browser does it, instead of measuring cap+descender ink: the line box (line-height x
     * font-size) is what decides where the following line starts.
     */
    private static final float LINE_DEFAULT = 1.2f, LINE_NOTE = 1.25f, LINE_DOC_TITLE = 1.15f,
            LINE_DOC_SUB = 1.3f;

    /**
     * Type sizes, held here rather than in the renderer because the row heights below are derived
     * from them. Sizes come from {@link NiloreTheme.Type}, where the CSS size is doubled (the
     * {@code FontRenderer} size argument renders at about half its value) and rounded to a whole
     * point, since AWT can only grid-fit a glyph at an integral size.
     */
    public static final float SZ_SMALL = NiloreTheme.Type.CAPTION, SZ_COUNT = NiloreTheme.Type.SMALL,
            SZ_GROUP = NiloreTheme.Type.SMALL, SZ_BODY = NiloreTheme.Type.BODY,
            SZ_LABEL = NiloreTheme.Type.SMALL, SZ_BRAND = NiloreTheme.Type.TITLE,
            SZ_NAV = NiloreTheme.Type.BODY, SZ_TITLE = NiloreTheme.Type.HEADLINE,
            SZ_DOC_TITLE = NiloreTheme.Type.DOC_TITLE;

    /** Height one line of text occupies in CSS: line-height x font-size. */
    public static float lineBox(float sizeArgument, float lineHeight) {
        return sizeArgument / 2f * lineHeight;
    }

    // sidebar
    public static final float SIDE_PAD_X = 10, SIDE_PAD_Y = 14, SIDE_GAP = 18;
    public static final float NAV_TOP = CONTENT_TOP + SIDE_PAD_Y;
    /** .side-label { padding: 0 10px 6px } */
    public static final float NAV_LABEL_LINE = lineBox(SZ_SMALL, LINE_DEFAULT),
            NAV_LABEL_H = NAV_LABEL_LINE + 6;
    public static final float NAV_LABEL_CENTER = NAV_TOP + NAV_LABEL_LINE / 2f - 2f;
    public static final float NAV_LABEL_PAD_X = 10;
    public static final float NAV_START = NAV_TOP + NAV_LABEL_H;
    public static final float NAV_H = 38, NAV_GAP = 4, NAV_RADIUS = NiloreTheme.Radius.CONTROL, NAV_PAD = 9,
            NAV_ICON = 22, NAV_ICON_GAP = 9;
    public static final float FOOTER_GAP = 12;
    public static final float FOOTER_H = (NAV_H + NAV_GAP) * 2 - NAV_GAP;

    // list pane
    public static final float SEARCH_PAD_TOP = 14, SEARCH_PAD_X = 20, SEARCH_PAD_BOTTOM = 10, SEARCH_H = 36;
    public static final float SEARCH_TOP = CONTENT_TOP + SEARCH_PAD_TOP;
    public static final float SEARCH_INNER_PAD = 12, SEARCH_ICON = 16, SEARCH_ICON_GAP = 7;

    public static final float LIST_TOP = SEARCH_TOP + SEARCH_H + SEARCH_PAD_BOTTOM;
    public static final float LIST_PAD_X = 8, LIST_PAD_TOP = 4, LIST_PAD_BOTTOM = 18;
    public static float LIST_CONTENT_W = LIST_W - LIST_PAD_X * 2;
    /** .list-group-title { padding: 8px 12px 6px; font-size: 12px } */
    public static final float LIST_GROUP_PAD_TOP = 8, LIST_GROUP_PAD_BOTTOM = 6, GROUP_TITLE_PAD_X = 12;
    public static final float GROUP_TITLE_LINE = lineBox(SZ_GROUP, LINE_DEFAULT),
            GROUP_TITLE_H = LIST_GROUP_PAD_TOP + GROUP_TITLE_LINE + LIST_GROUP_PAD_BOTTOM,
            LIST_GROUP_TITLE_CENTER = LIST_TOP + LIST_PAD_TOP + LIST_GROUP_PAD_TOP + GROUP_TITLE_LINE / 2f;
    /** .note-title / .note-desc { line-height: 1.25 }, .note-desc { margin-top: 4px } */
    public static final float NOTE_PAD_Y = 13, NOTE_SUB_GAP = 4;
    public static final float NOTE_TITLE_LINE = lineBox(SZ_TITLE, LINE_NOTE),
            NOTE_SUB_LINE = lineBox(SZ_BODY, LINE_NOTE);
    public static final float NOTE_TEXT_H = NOTE_TITLE_LINE + NOTE_SUB_GAP + NOTE_SUB_LINE;
    public static final float NOTE_H = Math.max(65, NOTE_PAD_Y * 2 + NOTE_TEXT_H);
    public static final float NOTE_RADIUS = NiloreTheme.Radius.ROW, NOTE_PAD_X = 12, NOTE_GAP = 12;
    public static final float SWITCH_W = 51, SWITCH_H = 31, SWITCH_KNOB = 27, SWITCH_PAD = 2;

    public static Rect LIST_VIEWPORT = new Rect(LIST_X, LIST_TOP, LIST_W, HEIGHT - LIST_TOP);

    // detail pane
    public static final float DETAIL_PAD_TOP = CONTENT_TOP + 16;
    public static final float DOC_PAD_X = 14, DOC_PAD_TOP = 6, DOC_PAD_BOTTOM = 16;
    /** .doc-title { line-height: 1.15 }, .doc-sub { margin-top: 6px; line-height: 1.3 } */
    public static final float DOC_SUB_GAP = 6;
    public static final float DOC_TITLE_LINE = lineBox(SZ_DOC_TITLE, LINE_DOC_TITLE),
            DOC_SUB_LINE = lineBox(SZ_BODY, LINE_DOC_SUB);
    public static final float DOC_TEXT_H = DOC_TITLE_LINE + DOC_SUB_GAP + DOC_SUB_LINE;
    public static final float DOC_H =
            Math.max(75, DOC_PAD_TOP + DOC_TEXT_H + DOC_PAD_BOTTOM);
    public static final float GROUP_MARGIN_X = 14, GROUP_MARGIN_BOTTOM = 20;
    public static float GROUP_X = DETAIL_X + GROUP_MARGIN_X;
    public static float GROUP_W = DETAIL_W - GROUP_MARGIN_X * 2;
    /** .group-title { margin: 0 8px 8px } */
    public static final float GROUP_TITLE_GAP = 8;
    public static final float GROUP_RADIUS = NiloreTheme.Radius.PANEL;

    public static final float ROW_PAD_X = 14, ROW_PAD_Y = 11, ROW_MIN_H = 48;
    /** .setting-row.column { padding: 12px 14px 14px } - the control follows the label directly. */
    public static final float COL_PAD_TOP = 12, COL_PAD_BOTTOM = 14, COL_BLOCK_GAP = 0;
    /** .label-text { line-height: 1.2 } */
    public static final float ROW_LABEL_H = lineBox(SZ_LABEL, LINE_DEFAULT);
    public static final float ROW_SWITCH_H =
            Math.max(ROW_PAD_Y * 2 + SWITCH_H, Math.max(ROW_MIN_H, ROW_LABEL_H + ROW_PAD_Y * 2));
    public static final float ROW_SELECT_H =
            Math.max(ROW_PAD_Y * 2 + NiloreTheme.Metrics.CONTROL_H, Math.max(ROW_MIN_H, ROW_LABEL_H + ROW_PAD_Y * 2));
    public static final float SELECT_H = NiloreTheme.Metrics.CONTROL_H, SELECT_RADIUS = NiloreTheme.Radius.CONTROL,
            SELECT_MIN_W = 132, SELECT_MAX_W = 176;
    public static final float SELECT_PAD_LEFT = 10, SELECT_PAD_RIGHT = 29, SELECT_CHEVRON = 12;
    public static final float SLIDER_H = 28, SLIDER_TRACK = 4, SLIDER_KNOB = 20;
    public static final float ROW_SLIDER_H =
            COL_PAD_TOP + ROW_LABEL_H + COL_BLOCK_GAP + SLIDER_H + COL_PAD_BOTTOM;
    /** .segmented { height: 30px; padding: 2px; gap: 2px }, .seg-btn { height: 26px } */
    public static final float SEGMENT_PAD = 2, SEGMENT_GAP = 2;
    public static final float SEGMENTED_H = 30;
    public static final float SEGMENT_RADIUS = NiloreTheme.Radius.CONTROL,
            SEGMENT_BTN_RADIUS = NiloreTheme.Radius.CONTROL;
    public static final float ROW_SEGMENTED_H =
            COL_PAD_TOP + ROW_LABEL_H + COL_BLOCK_GAP + SEGMENTED_H + COL_PAD_BOTTOM;

    public static Rect DETAIL_VIEWPORT = new Rect(DETAIL_X, DETAIL_PAD_TOP, DETAIL_W, HEIGHT - DETAIL_PAD_TOP);

    // dropdown
    public static final float MENU_PAD = 4, MENU_RADIUS = NiloreTheme.Radius.PANEL, MENU_MIN_W = 150;
    public static final float MENU_OPTION_H = 34, MENU_OPTION_RADIUS = NiloreTheme.Radius.CONTROL, MENU_CHECK = 14;

    // titlebar
    public static final float TRAFFIC_X = NiloreTheme.Metrics.TRAFFIC_PAD,
            TRAFFIC_Y = TITLEBAR_H / 2f;
    public static final float TRAFFIC_DOT = NiloreTheme.Metrics.TRAFFIC_DOT,
            TRAFFIC_GAP = NiloreTheme.Metrics.TRAFFIC_GAP;
    public static final float TRAFFIC_HIT = 22;

    // scrollbars
    public static final float SCROLL_GUTTER = 12, SCROLL_KNOB = 4;

    private NiloreLayout() {
    }

    /** Re-flows the panes for a window of the given design size. */
    public static void resize(float width, float height) {
        WIDTH = width;
        HEIGHT = height;
        float body = width - SIDEBAR_W;
        DETAIL_W = Math.max(300, body * .324f);
        LIST_W = body - DETAIL_W;
        LIST_X = SIDEBAR_W;
        DETAIL_X = SIDEBAR_W + LIST_W;
        LIST_CONTENT_W = LIST_W - LIST_PAD_X * 2;
        GROUP_X = DETAIL_X + GROUP_MARGIN_X;
        GROUP_W = DETAIL_W - GROUP_MARGIN_X * 2;
        WINDOW = new Rect(0, 0, width, height);
        LIST_VIEWPORT = new Rect(LIST_X, LIST_TOP, LIST_W, height - LIST_TOP);
        DETAIL_VIEWPORT = new Rect(DETAIL_X, DETAIL_PAD_TOP, DETAIL_W, height - DETAIL_PAD_TOP);
    }

    public record Rect(float x, float y, float width, float height) {
        public float right() {
            return x + width;
        }

        public float bottom() {
            return y + height;
        }

        public boolean contains(double px, double py) {
            return width > 0 && height > 0 && px >= x && px < right() && py >= y && py < bottom();
        }

        public Rect intersect(Rect other) {
            float left = Math.max(x, other.x), top = Math.max(y, other.y);
            return new Rect(left, top, Math.max(0, Math.min(right(), other.right()) - left),
                    Math.max(0, Math.min(bottom(), other.bottom()) - top));
        }

        public Rect offset(float dx, float dy) {
            return new Rect(x + dx, y + dy, width, height);
        }
    }

    public record Viewport(float x, float y, float scale) {
        public Rect screen(Rect local, float openingScale) {
            float size = scale * openingScale;
            return new Rect(x + WIDTH * scale / 2 + (local.x() - WIDTH / 2) * size,
                    y + HEIGHT * scale / 2 + (local.y() - HEIGHT / 2) * size,
                    local.width() * size, local.height() * size);
        }

        public float localX(double screenX, float openingScale) {
            return WIDTH / 2 + ((float) screenX - x - WIDTH * scale / 2) / (scale * openingScale);
        }

        public float localY(double screenY, float openingScale) {
            return HEIGHT / 2 + ((float) screenY - y - HEIGHT * scale / 2) / (scale * openingScale);
        }
    }

    public static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    public static Viewport viewport(int width, int height, float x, float y) {
        float fit = Math.min(1, Math.min((width - 16f) / NATURAL_W, (height - 16f) / NATURAL_H));
        float scale = Math.min(1f, fit * WINDOW_SCALE);
        return new Viewport(clamp(x, 4, Math.max(4, width - NATURAL_W * scale - 4)),
                clamp(y, 4, Math.max(4, height - NATURAL_H * scale - 4)), scale);
    }

    public static Viewport centered(int width, int height) {
        Viewport size = viewport(width, height, 4, 4);
        return viewport(width, height, (width - NATURAL_W * size.scale) / 2, (height - NATURAL_H * size.scale) / 2);
    }

    /** Scale factor shared by every window, so panes never disagree about pixel density. */
    public static float scale(int screenWidth, int screenHeight) {
        float fit = Math.min(1, Math.min((screenWidth - 16f) / NATURAL_W, (screenHeight - 16f) / NATURAL_H));
        return Math.max(.4f, Math.min(1f, fit * WINDOW_SCALE));
    }

    public static Rect navItem(int index) {
        return new Rect(SIDE_PAD_X, NAV_START + index * (NAV_H + NAV_GAP), SIDEBAR_W - SIDE_PAD_X * 2, NAV_H);
    }

    public static Rect footerItem(int index) {
        return new Rect(SIDE_PAD_X, HEIGHT - SIDE_PAD_Y - FOOTER_H + index * (NAV_H + NAV_GAP),
                SIDEBAR_W - SIDE_PAD_X * 2, NAV_H);
    }

    public static Rect searchBox() {
        return new Rect(LIST_X + SEARCH_PAD_X, SEARCH_TOP, LIST_W - SEARCH_PAD_X * 2, SEARCH_H);
    }

    public static Rect noteRow(int index, float scroll) {
        return new Rect(LIST_X + LIST_PAD_X, LIST_TOP + LIST_PAD_TOP + GROUP_TITLE_H + index * NOTE_H - scroll,
                LIST_CONTENT_W, NOTE_H);
    }

    public static Rect rowSwitch(Rect row) {
        return new Rect(row.right() - NOTE_PAD_X - SWITCH_W, row.y() + (row.height() - SWITCH_H) / 2f,
                SWITCH_W, SWITCH_H);
    }

    /**
     * Cap centres of the two lines of a list row. The whole text block is centred against the row
     * (the reference's {@code align-items: center}) and each line centres its cap inside its own
     * line box, which is where the browser puts it.
     */
    public static float noteTitleY(Rect row) {
        return row.y() + (row.height() - NOTE_TEXT_H) / 2f + NOTE_TITLE_LINE / 2f;
    }

    public static float noteSubY(Rect row) {
        return row.y() + (row.height() - NOTE_TEXT_H) / 2f + NOTE_TITLE_LINE + NOTE_SUB_GAP + NOTE_SUB_LINE / 2f;
    }

    public static Rect docHead() {
        return new Rect(DETAIL_X, DETAIL_PAD_TOP, DETAIL_W, DOC_H);
    }

    public static float docTitleY(Rect head) {
        return head.y() + (head.height() - DOC_TEXT_H) / 2f + DOC_TITLE_LINE / 2f;
    }

    public static float docSubY(Rect head) {
        return head.y() + (head.height() - DOC_TEXT_H) / 2f + DOC_TITLE_LINE + DOC_SUB_GAP + DOC_SUB_LINE / 2f;
    }

    public static Rect docSwitch() {
        return new Rect(DETAIL_X + DETAIL_W - DOC_PAD_X - SWITCH_W, DETAIL_PAD_TOP + (DOC_H - SWITCH_H) / 2f,
                SWITCH_W, SWITCH_H);
    }

    /** Content box of a setting row, i.e. the row minus its horizontal padding. */
    public static float rowContentX() {
        return GROUP_X + ROW_PAD_X;
    }

    public static float rowContentW() {
        return GROUP_W - ROW_PAD_X * 2;
    }

    public static Rect trafficLight(int index) {
        float x = TRAFFIC_X + index * (TRAFFIC_DOT + TRAFFIC_GAP);
        return new Rect(x, TRAFFIC_Y - TRAFFIC_DOT / 2f, TRAFFIC_DOT, TRAFFIC_DOT);
    }

    public static Rect thumb(float scroll, float maximum, Rect viewport) {
        float height = Math.max(30, viewport.height() * viewport.height() / (viewport.height() + maximum));
        float travel = maximum <= 0 ? 0 : scroll / maximum * (viewport.height() - height);
        return new Rect(viewport.right() - SCROLL_GUTTER + (SCROLL_GUTTER - SCROLL_KNOB) / 2,
                viewport.y() + travel, SCROLL_KNOB, height);
    }

    /** Second-order critically damped spring used for every hover / open / selection motion. */
    public static final class Motion {
        private final double rate;
        private double time = Double.NaN;
        private float value, target;
        private double velocity;

        public Motion(float value, double rate) {
            this.value = this.target = value;
            this.rate = rate;
        }

        public float to(float target, double now) {
            if (!Double.isNaN(time)) {
                double dt = Math.max(0, now - time), error = value - this.target;
                double c = velocity + rate * error, decay = Math.exp(-rate * dt);
                // Analytic critical damping keeps velocity continuous when the target changes.
                value = this.target + (float) ((error + c * dt) * decay);
                velocity = (velocity - rate * c * dt) * decay;
                if (Math.abs(value - this.target) < .0001f && Math.abs(velocity) < .001) {
                    value = this.target;
                    velocity = 0;
                }
            }
            time = now;
            this.target = target;
            return value;
        }

        public void snap(float value, double now) {
            this.value = this.target = value;
            velocity = 0;
            time = now;
        }

        public float value() {
            return value;
        }
    }
}
