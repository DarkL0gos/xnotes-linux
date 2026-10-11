#define _DEFAULT_SOURCE /* usleep */
/*
 * Drives libxnotes.so through xnotes.h alone, the way the Qt host will: a recording renderer and
 * host, a pen stroke, undo/redo, save and reopen, and error reporting.
 * Usage: capi_test <scratch dir>
 */
#include "xnotes.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Test-only export: run a full garbage collection (cleaners follow on their own thread). */
void xn_test_collect_garbage(void);

static int failures = 0;
#define CHECK(cond, ...)                                                   \
    do {                                                                   \
        if (!(cond)) {                                                     \
            fprintf(stderr, "FAIL %s:%d: ", __FILE__, __LINE__);           \
            fprintf(stderr, __VA_ARGS__);                                  \
            fputc('\n', stderr);                                           \
            failures++;                                                    \
        }                                                                  \
    } while (0)

/* --- a recording renderer --- */

typedef struct {
    int saves, restores, fills, polygons, ellipses, ribbons, ribbon_points, strokes, surfaces, texts, layers;
} counts;

typedef struct {
    counts* c;
} target;

static void r_save(void* r) { ((target*)r)->c->saves++; }
static void r_restore(void* r) { ((target*)r)->c->restores++; }
static void r_save_layer(void* r, double x, double y, double w, double h, double a, int b) {
    ((target*)r)->c->layers++;
    ((target*)r)->c->saves++;
}
static void r_translate(void* r, double dx, double dy) {}
static void r_scale(void* r, double sx, double sy) {}
static void r_rotate(void* r, double d) {}
static void r_clip(void* r, double x, double y, double w, double h) {}
static void r_clear(void* r) {}
static void r_fill_rect(void* r, double x, double y, double w, double h, xn_rgba c) { ((target*)r)->c->fills++; }
static void r_fill_polygon(void* r, const double* xy, int n, xn_rgba c, int rule) { ((target*)r)->c->polygons++; }
static void r_fill_ellipse(void* r, double cx, double cy, double rx, double ry, xn_rgba c) { ((target*)r)->c->ellipses++; }
static void r_fill_disk_ribbon(void* r, const float* centers, const float* radii, int from, int count, xn_rgba c) {
    ((target*)r)->c->ribbons++;
    ((target*)r)->c->ribbon_points += count;
    /* Touch the arrays the way a real host reads them. */
    for (int i = from; i < from + count; i++) {
        if (radii[i] < 0 || centers[2 * i] != centers[2 * i]) ((target*)r)->c->ribbons += 1000000;
    }
}
static void r_stroke_polyline(void* r, const double* xy, int n, int closed, const xn_pen* p) { ((target*)r)->c->strokes++; }
static void r_stroke_rect(void* r, double x, double y, double w, double h, const xn_pen* p) { ((target*)r)->c->strokes++; }
static void r_stroke_ellipse(void* r, double cx, double cy, double rx, double ry, const xn_pen* p) { ((target*)r)->c->strokes++; }
static void s_check(void* s);
static void r_draw_surface(void* r, void* s, const double* d, const double* src, double a, int b) {
    s_check(s);
    ((target*)r)->c->surfaces++;
}
static void r_draw_image(void* r, const char* path, double x, double y, double w, double h, int o, double a) {}
static void r_draw_text(void* r, const uint16_t* t, int n, double x, double y, double w, double h, const xn_font* f,
                        xn_rgba c, int ww, int al, int at) { ((target*)r)->c->texts++; }
static void r_draw_text_run(void* r, const uint16_t* t, int n, double x, double b, const xn_font* f, xn_rgba c) {
    ((target*)r)->c->texts++;
}

static const xn_renderer_vt vt = {
    r_save, r_restore, r_save_layer, r_translate, r_scale, r_rotate, r_clip, r_clear,
    r_fill_rect, r_fill_polygon, r_fill_ellipse, r_fill_disk_ribbon,
    r_stroke_polyline, r_stroke_rect, r_stroke_ellipse,
    r_draw_surface, r_draw_image, r_draw_text, r_draw_text_run,
};

/* --- the host --- */

typedef struct {
    counts screen;       /* what xn_editor_paint drew */
    counts offscreen;    /* what went into page cache surfaces */
    target screen_target;
    target surface_target;
    int surfaces_live;
    int renders, content, view;
    int last_notice;
    uint64_t delayed[64];
    int delayed_n;
    uint64_t frames[64];
    int frames_n;
    int menu_shown, menu_calls, context_calls;
    int text_open, text_calls;
    int pdf_draws;
    char pdf_path[1024];
    double text_x, text_font;
    char tool[32];
} host_state;

/*
 * Surfaces are never freed while the test runs: a released one goes on a stack and the next
 * create hands the same address out again, as malloc tends to. Any use of a released surface,
 * or a second release, is counted, so a core that tracks surfaces by address gets caught.
 */
typedef struct {
    int w, h, alive;
} surface;

static surface* spare[4096];
static int spare_n;
static int stale_uses, double_releases, creates, reuses;

static void* s_create(void* ctx, int w, int h, double dpr) {
    host_state* hs = ctx;
    hs->surfaces_live++;
    creates++;
    if (spare_n > 0) reuses++;
    surface* s = spare_n > 0 ? spare[--spare_n] : malloc(sizeof(surface));
    s->w = w;
    s->h = h;
    s->alive = 1;
    return s;
}
static void s_check(void* s) {
    if (!((surface*)s)->alive) stale_uses++;
}
static void s_fill(void* ctx, void* s, xn_rgba c) { s_check(s); }
static void* s_renderer(void* ctx, void* s) {
    s_check(s);
    return &((host_state*)ctx)->surface_target;
}
static void s_release(void* ctx, void* s) {
    surface* sf = s;
    if (!sf->alive) {
        double_releases++;
        return;
    }
    ((host_state*)ctx)->surfaces_live--;
    sf->alive = 0;
    if (spare_n < 4096) spare[spare_n++] = sf;
}
static void t_measure(void* ctx, const uint16_t* t, int n, const xn_font* f, double wrap, int ww, double* out) {
    out[0] = 0;
    out[1] = 0;
    out[2] = wrap;
    out[3] = f->point_size * 1.3 * (1 + (n * f->point_size * 0.5) / (wrap > 1 ? wrap : 1));
}
static void t_metrics(void* ctx, const xn_font* f, double* a, double* d) {
    *a = f->point_size * 0.8;
    *d = f->point_size * 0.2;
}
static void t_advances(void* ctx, const uint16_t* t, int n, const xn_font* f, double* out) {
    for (int i = 0; i < n; i++) out[i] = f->point_size * 0.5;
}
/* "Images" whose path ends in .png probe as 800x600; everything else is unreadable. */
static int i_probe(void* ctx, const char* path, int* w, int* h) {
    size_t n = strlen(path);
    if (n < 4 || strcmp(path + n - 4, ".png") != 0) return 0;
    *w = 800;
    *h = 600;
    return 1;
}
static void h_render(void* ctx) { ((host_state*)ctx)->renders++; }
static void h_content(void* ctx) { ((host_state*)ctx)->content++; }
static void h_view(void* ctx) { ((host_state*)ctx)->view++; }
static void h_notice(void* ctx, int code) { ((host_state*)ctx)->last_notice = code; }
static void h_post(void* ctx, uint64_t task, int64_t delay) {
    host_state* hs = ctx;
    if (hs->delayed_n < 64) hs->delayed[hs->delayed_n++] = task;
}
static void h_cancel(void* ctx, uint64_t task) {
    host_state* hs = ctx;
    for (int i = 0; i < hs->delayed_n; i++)
        if (hs->delayed[i] == task) hs->delayed[i] = 0;
}
static void h_frame(void* ctx, uint64_t task) {
    host_state* hs = ctx;
    if (hs->frames_n < 64) hs->frames[hs->frames_n++] = task;
}

static void h_selection_menu(void* ctx, int shown, double x, double y, double w, double h) {
    host_state* hs = ctx;
    hs->menu_shown = shown && w > 0 && h > 0;
    hs->menu_calls++;
}
static void h_context_menu(void* ctx, double x, double y, int on_locked) { ((host_state*)ctx)->context_calls++; }
static void h_tool_changed(void* ctx, const char* tool) {
    snprintf(((host_state*)ctx)->tool, sizeof ((host_state*)ctx)->tool, "%s", tool);
}

static void h_text_edit(void* ctx, const xn_text_field* f) {
    host_state* hs = ctx;
    hs->text_calls++;
    hs->text_open = f != NULL;
    if (f) {
        hs->text_x = f->x;
        hs->text_font = f->font_px;
    }
}

/* "PDFs": any path ending in .pdf has 3 A4 pages. */
static int is_pdf(const char* path) {
    size_t n = strlen(path);
    return n >= 4 && strcmp(path + n - 4, ".pdf") == 0;
}
static int p_count(void* ctx, const char* path) { return is_pdf(path) ? 3 : -1; }
static int p_size(void* ctx, const char* path, int page, double* w, double* h) {
    if (!is_pdf(path) || page < 0 || page >= 3) return 0;
    *w = 595;
    *h = 842;
    return 1;
}
static int p_draw(void* ctx, void* r, const char* path, int page, int fw, int fh, int rx, int ry, int rw, int rh,
                  double x, double y, double w, double h) {
    host_state* hs = ctx;
    if (fw > 0 && fh > 0 && rw > 0 && rh > 0 && rx + rw <= fw + 1 && ry + rh <= fh + 1 && w > 0 && h > 0) hs->pdf_draws++;
    snprintf(hs->pdf_path, sizeof hs->pdf_path, "%s", path);
    return 1;
}

static xn_host make_host(host_state* hs) {
    memset(hs, 0, sizeof(*hs));
    hs->screen_target.c = &hs->screen;
    hs->surface_target.c = &hs->offscreen;
    xn_host h = {
        hs, &vt,
        s_create, s_fill, s_renderer, s_release,
        t_measure, t_metrics, t_advances,
        i_probe,
        h_render, h_content, h_view, h_notice,
        h_post, h_cancel, h_frame,
        h_selection_menu, h_context_menu, h_tool_changed, h_text_edit,
        p_count, p_size, p_draw,
        NULL, /* wake: page caches are drawn as they are asked for */
    };
    return h;
}

static void pen(xn_editor* e, int action, float x, float y, int64_t t, int history, const float* hx, const float* hy) {
    xn_pointer p = {0, XN_TOOL_TYPE_STYLUS, x, y, 0.6f};
    xn_pointer hist[16];
    int64_t times[16];
    for (int i = 0; i < history; i++) {
        xn_pointer q = {0, XN_TOOL_TYPE_STYLUS, hx[i], hy[i], 0.4f + 0.02f * i};
        hist[i] = q;
        times[i] = t - (history - i);
    }
    xn_pointer_event ev = {action, 0, 0, t, 1, &p, history, times, hist};
    xn_editor_touch(e, &ev);
}

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <scratch dir>\n", argv[0]);
        return 2;
    }
    CHECK(xn_api_version() == XN_API_VERSION, "api version %d", xn_api_version());

    host_state hs;
    xn_host host = make_host(&hs);

    xn_note* note = xn_note_new(2);
    CHECK(note != NULL, "xn_note_new");
    CHECK(xn_note_page_count(note) == 2, "page count");
    xn_editor* ed = xn_editor_create(note, &host);
    CHECK(ed != NULL, "xn_editor_create");
    xn_editor_set_viewport(ed, 800, 1000, 1.0);
    CHECK(xn_editor_zoom(ed) > 0, "zoom after the initial fit");

    xn_editor_paint(ed, &hs.screen_target);
    CHECK(hs.screen.fills > 0, "paper was filled");
    CHECK(xn_editor_background_pending(ed) == 0, "no background work without wake");
    CHECK(hs.screen.saves == hs.screen.restores, "balanced save/restore (%d/%d)", hs.screen.saves, hs.screen.restores);

    /* A pen stroke across the first page, with coalesced samples on every move. */
    CHECK(xn_editor_set_tool(ed, "pen") == 1, "set pen");
    CHECK(xn_editor_set_tool(ed, "no-such-tool") == 0, "unknown tool rejected");
    xn_editor_set_ink_color(ed, 0x102030FFu);
    int64_t t = 1000;
    pen(ed, XN_ACTION_DOWN, 200, 300, t, 0, NULL, NULL);
    for (int step = 1; step <= 20; step++) {
        float hx[3], hy[3];
        for (int i = 0; i < 3; i++) {
            hx[i] = 200 + step * 12 - (3 - i) * 3;
            hy[i] = 300 + (step % 5) * 4 + i;
        }
        t += 4;
        pen(ed, XN_ACTION_MOVE, 200 + step * 12, 300 + (step % 5) * 4 + 3, t, 3, hx, hy);
        if (step == 10) {
            memset(&hs.screen, 0, sizeof(hs.screen));
            xn_editor_paint(ed, &hs.screen_target);
            CHECK(hs.screen.ribbons > 0 && hs.screen.ribbon_points > 1, "the live stroke is drawn as a ribbon mid-gesture");
            CHECK(hs.screen.ribbons < 1000000, "ribbon arrays hold sane values");
        }
    }
    pen(ed, XN_ACTION_UP, 440, 312, t + 4, 0, NULL, NULL);

    int page = xn_editor_current_page(ed);
    CHECK(xn_note_item_count(note, page) == 1, "one stroke filed on page %d (got %d)", page, xn_note_item_count(note, page));
    CHECK(xn_note_is_dirty(note) == 1, "note is dirty");
    CHECK(xn_editor_can_undo(ed) == 1, "can undo");
    CHECK(hs.content > 0 && hs.renders > 0, "host told of content (%d) and renders (%d)", hs.content, hs.renders);

    memset(&hs.screen, 0, sizeof(hs.screen));
    xn_editor_paint(ed, &hs.screen_target);
    CHECK(hs.screen.surfaces > 0, "page caches are blitted");
    CHECK(hs.offscreen.ribbons > 0, "the filed stroke went into a page cache");

    xn_editor_undo(ed);
    CHECK(xn_note_item_count(note, page) == 0, "undo removes it");
    CHECK(xn_editor_can_redo(ed) == 1, "can redo");
    xn_editor_redo(ed);
    CHECK(xn_note_item_count(note, page) == 1, "redo restores it");

    /* v2: selection, its menu, clipboard, restyle, lock. */
    CHECK(strcmp(xn_editor_tool(ed), "pen") == 0, "armed tool is pen (%s)", xn_editor_tool(ed));
    xn_editor_select_all(ed);
    CHECK(xn_editor_has_selection(ed) == 1, "select all selects the stroke");
    double rect[4] = {0};
    CHECK(xn_editor_selection_rect(ed, rect) == 1 && rect[2] > 0 && rect[3] > 0, "selection rect %g %g %g %g",
          rect[0], rect[1], rect[2], rect[3]);
    xn_rgba color = 0;
    double width = 0;
    CHECK(xn_editor_selection_style(ed, &color, &width) == 1 && color == 0x102030FFu, "selection style %08x", color);
    xn_editor_restyle_selection(ed, 1, 0xFF0000FFu, 1, 12.0, 1);
    xn_editor_restyle_selection(ed, 1, 0x00FF00FFu, 0, 0, 1);
    xn_editor_restyle_selection(ed, 0, 0, 0, 0, 0);
    CHECK(xn_editor_selection_style(ed, &color, &width) == 1 && color == 0x00FF00FFu && width == 12.0,
          "restyled %08x %g", color, width);
    xn_editor_undo(ed);
    CHECK(xn_editor_has_selection(ed) == 0, "undo puts the selection away");
    xn_editor_select_all(ed);
    CHECK(xn_editor_selection_style(ed, &color, &width) == 1 && color == 0x102030FFu, "one undo reverts the restyle");
    xn_editor_copy(ed);
    CHECK(xn_editor_can_paste(ed) == 1, "copied");
    xn_editor_paste_at(ed, 300, 600);
    CHECK(xn_note_item_count(note, page) == 2, "pasted (%d)", xn_note_item_count(note, page));
    xn_editor_duplicate(ed);
    CHECK(xn_note_item_count(note, page) == 3, "duplicated");
    xn_editor_bring_to_front(ed);
    xn_editor_cut(ed);
    CHECK(xn_note_item_count(note, page) == 2 && xn_editor_has_selection(ed) == 0, "cut");
    xn_editor_select_all(ed);
    xn_editor_lock_selection(ed);
    CHECK(xn_editor_has_selection(ed) == 0, "lock deselects");
    xn_editor_select_all(ed);
    CHECK(xn_editor_has_selection(ed) == 0, "locked items cannot be selected");
    xn_editor_undo(ed); /* unlock */
    xn_editor_undo(ed); /* uncut */
    xn_editor_undo(ed); /* undo duplicate */
    xn_editor_undo(ed); /* undo paste */
    CHECK(xn_note_item_count(note, page) == 1, "undo back to one stroke (%d)", xn_note_item_count(note, page));
    xn_editor_escape(ed);

    /* v2: tool widths and shapes. */
    xn_editor_set_tool_width(ed, "pen", 7.5);
    CHECK(xn_editor_tool_width(ed, "pen") == 7.5, "pen width");
    xn_editor_set_tool_width(ed, "pen", 1000);
    CHECK(xn_editor_tool_width(ed, "pen") == XN_STYLE_MAX_WIDTH, "pen width is clamped");
    xn_shape_style shape = {"ellipse", 4.0, 1, 0.3, 0};
    xn_editor_set_shape_style(ed, &shape);
    CHECK(xn_editor_set_tool(ed, "shape") == 1, "shape tool");
    pen(ed, XN_ACTION_DOWN, 200, 500, t + 100, 0, NULL, NULL);
    for (int i = 1; i <= 10; i++) pen(ed, XN_ACTION_MOVE, 200 + i * 10, 500 + i * 6, t + 100 + i * 8, 0, NULL, NULL);
    pen(ed, XN_ACTION_UP, 300, 560, t + 190, 0, NULL, NULL);
    CHECK(xn_note_item_count(note, page) == 2, "the shape was added (%d)", xn_note_item_count(note, page));
    memset(&hs.screen, 0, sizeof(hs.screen));
    xn_editor_paint(ed, &hs.screen_target);
    xn_editor_undo(ed);
    xn_editor_set_tool(ed, "pen");

    /* v2: a text box. A tap with the text box tool opens a field; typing fills it; commit files it. */
    CHECK(xn_editor_set_tool(ed, "text_box") == 1, "text box tool");
    xn_editor_set_text_size(ed, 20);
    pen(ed, XN_ACTION_DOWN, 250, 700, t + 300, 0, NULL, NULL);
    pen(ed, XN_ACTION_UP, 250, 700, t + 340, 0, NULL, NULL);
    CHECK(hs.text_open == 1, "a text field opened (%d calls)", hs.text_calls);
    CHECK(hs.text_x > 200 && hs.text_x < 300 && hs.text_font > 20, "field at %g, font %g px", hs.text_x, hs.text_font);
    const uint16_t hello[] = {'H', 'e', 'l', 'l', 'o', 0x0416};
    xn_editor_text_update(ed, hello, 6);
    xn_text_field field;
    CHECK(xn_editor_text_field(ed, &field) == 1 && field.width > 0 && field.text == NULL, "field geometry");
    xn_editor_text_commit(ed);
    CHECK(hs.text_open == 0, "commit closes the field");
    CHECK(xn_note_item_count(note, page) == 2, "the box was filed (%d)", xn_note_item_count(note, page));
    CHECK(xn_editor_text_field(ed, &field) == 0, "no field after commit");
    /* An empty box is dropped. */
    pen(ed, XN_ACTION_DOWN, 250, 800, t + 400, 0, NULL, NULL);
    pen(ed, XN_ACTION_UP, 250, 800, t + 440, 0, NULL, NULL);
    CHECK(hs.text_open == 1, "a second field");
    xn_editor_text_commit(ed);
    CHECK(xn_note_item_count(note, page) == 2, "an empty box is dropped");
    xn_editor_undo(ed);
    CHECK(xn_note_item_count(note, page) == 1, "undo removes the box");
    xn_editor_set_tool(ed, "pen");

    /* v2: frame damage. Writing repaints only around the pen; anything else repaints in full. */
    double dmg[4];
    xn_editor_paint(ed, &hs.screen_target);
    xn_editor_set_tool(ed, "pen");
    xn_editor_paint(ed, &hs.screen_target);
    pen(ed, XN_ACTION_DOWN, 300, 600, t + 600, 0, NULL, NULL);
    xn_editor_paint(ed, &hs.screen_target);
    for (int i = 1; i <= 30; i++) {
        pen(ed, XN_ACTION_MOVE, 300 + i * 5, 600 + (i % 3), t + 600 + i * 4, 0, NULL, NULL);
        if (i == 30) {
            CHECK(xn_editor_frame_damage(ed, dmg) == 1, "a growing stroke damages part of the view");
            CHECK(dmg[2] < 200 && dmg[3] < 200 && dmg[0] > 250 && dmg[0] + dmg[2] < 500,
                  "around the pen: %g %g %g %g", dmg[0], dmg[1], dmg[2], dmg[3]);
        }
        xn_editor_paint(ed, &hs.screen_target);
    }
    pen(ed, XN_ACTION_UP, 450, 600, t + 740, 0, NULL, NULL);
    CHECK(xn_editor_frame_damage(ed, dmg) == 0, "filing the stroke repaints in full");
    xn_editor_paint(ed, &hs.screen_target);
    xn_editor_scroll_by(ed, 0, 10);
    CHECK(xn_editor_frame_damage(ed, dmg) == 0, "scrolling repaints in full");
    xn_editor_scroll_by(ed, 0, -10);
    xn_editor_paint(ed, &hs.screen_target);
    xn_editor_undo(ed);

    /* v2: scroll bars. */
    double sc[6];
    xn_editor_scroll_info(ed, sc);
    CHECK(sc[3] > 0 && sc[4] == 800 && sc[5] == 1000, "scroll range %g, viewport %gx%g", sc[3], sc[4], sc[5]);
    xn_editor_scroll_to(ed, 0, sc[3] / 2);
    xn_editor_scroll_info(ed, sc);
    CHECK(sc[1] > 0 && sc[1] <= sc[3], "scrolled to %g of %g", sc[1], sc[3]);
    xn_editor_scroll_to(ed, 0, 1e9);
    xn_editor_scroll_info(ed, sc);
    CHECK(sc[1] == sc[3], "scrolling is clamped");
    xn_editor_scroll_to(ed, 0, 0);

    /* v2: eraser mode and size. A stroke across the page; an area eraser crossing its middle
       cuts it in two, a stroke eraser removes it. */
    CHECK(xn_editor_erase_mode(ed) == XN_ERASE_STROKE, "the eraser takes whole strokes by default");
    CHECK(xn_editor_tool_width(ed, "eraser") == 24.0, "eraser radius 24");
    int before = xn_note_item_count(note, page);
    xn_editor_set_tool(ed, "pen");
    pen(ed, XN_ACTION_DOWN, 150, 850, t + 900, 0, NULL, NULL);
    for (int i = 1; i <= 40; i++) pen(ed, XN_ACTION_MOVE, 150 + i * 12, 850, t + 900 + i * 4, 0, NULL, NULL);
    pen(ed, XN_ACTION_UP, 630, 850, t + 1070, 0, NULL, NULL);
    CHECK(xn_note_item_count(note, page) == before + 1, "a stroke to erase");
    xn_editor_set_erase_mode(ed, XN_ERASE_AREA);
    CHECK(xn_editor_erase_mode(ed) == XN_ERASE_AREA, "area mode");
    xn_editor_set_tool_width(ed, "eraser", 10);
    xn_editor_set_tool(ed, "eraser");
    pen(ed, XN_ACTION_DOWN, 390, 820, t + 1100, 0, NULL, NULL);
    for (int i = 1; i <= 12; i++) pen(ed, XN_ACTION_MOVE, 390, 820 + i * 5, t + 1100 + i * 4, 0, NULL, NULL);
    pen(ed, XN_ACTION_UP, 390, 880, t + 1160, 0, NULL, NULL);
    CHECK(xn_note_item_count(note, page) == before + 2, "an area eraser splits the stroke (%d)", xn_note_item_count(note, page) - before);
    xn_editor_set_erase_mode(ed, XN_ERASE_STROKE);
    pen(ed, XN_ACTION_DOWN, 250, 820, t + 1200, 0, NULL, NULL);
    for (int i = 1; i <= 12; i++) pen(ed, XN_ACTION_MOVE, 250, 820 + i * 5, t + 1200 + i * 4, 0, NULL, NULL);
    pen(ed, XN_ACTION_UP, 250, 880, t + 1260, 0, NULL, NULL);
    CHECK(xn_note_item_count(note, page) == before + 1, "a stroke eraser removes the piece it touched");
    xn_editor_undo(ed);
    xn_editor_undo(ed);
    xn_editor_undo(ed);
    CHECK(xn_note_item_count(note, page) == before, "undone");
    xn_editor_set_tool_width(ed, "eraser", 24);
    xn_editor_set_tool(ed, "pen");

    /* v2: page style. */
    xn_page_style ps;
    xn_editor_page_style(ed, 1, &ps);
    CHECK(ps.pattern == XN_PATTERN_INHERIT && !ps.has_page_color && ps.spacing <= 0, "a new note overrides nothing");
    xn_page_style grid = {1, 0xF7F3E9FFu, XN_PATTERN_GRID, 1, 0x3060C080u, 40.0};
    int dirtyBefore = xn_note_is_dirty(note);
    xn_editor_set_page_style(ed, 1, &grid);
    xn_editor_page_style(ed, 1, &ps);
    CHECK(ps.pattern == XN_PATTERN_GRID && ps.page_color == 0xF7F3E9FFu && ps.pattern_color == 0x3060C080u && ps.spacing == 40.0,
          "all pages: grid on cream");
    CHECK(xn_note_is_dirty(note) == 1, "a style change marks the note changed (was %d)", dirtyBefore);
    xn_page_style lines = {0, 0, XN_PATTERN_LINES, 0, 0, 1000.0};
    xn_editor_set_page_style(ed, 0, &lines);
    xn_editor_page_style(ed, 0, &ps);
    CHECK(ps.pattern == XN_PATTERN_LINES && ps.spacing == XN_PATTERN_MAX_SPACING && !ps.has_page_color, "current page: lines, clamped");
    xn_editor_page_style(ed, 1, &ps);
    CHECK(ps.pattern == XN_PATTERN_GRID, "the note keeps its grid");
    memset(&hs.offscreen, 0, sizeof hs.offscreen);
    xn_editor_paint(ed, &hs.screen_target);

    /* v2: images. */
    CHECK(xn_editor_insert_image(ed, "/nonexistent/file.txt", 0, 0, 0) == 0, "an unreadable image is refused");
    CHECK(xn_editor_insert_image(ed, "/tmp/picture.png", 1, 400, 500) == 1, "insert an image");
    CHECK(xn_note_item_count(note, page) == 2, "the image is on the page (%d)", xn_note_item_count(note, page));
    xn_editor_undo(ed);
    CHECK(xn_note_item_count(note, page) == 1, "undo removes it");

    /* Pages and notices. */
    xn_editor_add_page(ed);
    CHECK(xn_note_page_count(note) == 3, "add page");
    xn_editor_undo(ed);
    CHECK(xn_note_page_count(note) == 2, "undo add page");

    /* The pages panel. */
    {
        const int64_t first = xn_note_page_id(note, 0), second = xn_note_page_id(note, 1);
        CHECK(first != 0 && second != 0 && first != second, "pages have ids");
        CHECK(xn_note_page_id(note, 5) == 0, "no id past the end");
        xn_editor_insert_page_after(ed, -1);
        CHECK(xn_note_page_count(note) == 3 && xn_editor_current_page(ed) == 0, "insert before the first");
        CHECK(xn_note_page_id(note, 1) == first, "ids move with their pages");
        int fresh[] = {0};
        xn_editor_delete_pages(ed, fresh, 1);
        CHECK(xn_note_page_count(note) == 2 && xn_note_page_id(note, 0) == first, "delete a page");
        int all[] = {1, 0, 1};
        xn_editor_delete_pages(ed, all, 3);
        CHECK(xn_note_page_count(note) == 2, "the last page stays");
        CHECK(xn_editor_can_paste_pages(ed) == 0, "nothing copied yet");
        int one[] = {page};  /* the page with the stroke */
        xn_editor_copy_pages(ed, one, 1);
        CHECK(xn_editor_can_paste_pages(ed) == 1, "copied");
        xn_editor_paste_pages_after(ed, 1);
        CHECK(xn_note_page_count(note) == 3 && xn_editor_current_page(ed) == 2, "paste at the end");
        const int items = xn_note_item_count(note, 2);
        CHECK(items > 0 && items == xn_note_item_count(note, page), "the copy has the page's items");
        CHECK(xn_note_page_id(note, 2) != xn_note_page_id(note, page), "a copy is a page of its own");
        xn_editor_erase_page(ed, 2);
        CHECK(xn_note_item_count(note, 2) == 0, "erase a page");
        xn_editor_undo(ed);
        CHECK(xn_note_item_count(note, 2) == items, "undo erase");
        int cut[] = {2};
        xn_editor_cut_pages(ed, cut, 1);
        CHECK(xn_note_page_count(note) == 2, "cut a page");
        xn_editor_undo(ed);
        xn_editor_undo(ed);
        CHECK(xn_note_page_count(note) == 2 && xn_note_page_id(note, 0) == first && xn_note_page_id(note, 1) == second,
              "undo back to the two pages");
    }

    /* Save, reopen. */
    char path[1024], work[1024], bad[1024];
    snprintf(path, sizeof path, "%s/capi.xnote", argv[1]);
    snprintf(work, sizeof work, "%s", argv[1]);
    char* err = NULL;
    CHECK(xn_note_save(note, path, &host, &err) == 1, "save: %s", err ? err : "");
    CHECK(xn_note_is_dirty(note) == 0, "clean after save");
    xn_note* back = xn_note_open(path, work, &host, &err);
    CHECK(back != NULL, "reopen: %s", err ? err : "");
    if (back) {
        CHECK(xn_note_page_count(back) == 2, "reopened pages");
        CHECK(xn_note_item_count(back, page) == 1, "reopened stroke");
        xn_note_close(back);
    }

    /* Errors come back as messages, never as crashes. */
    snprintf(bad, sizeof bad, "%s/does-not-exist.xnote", argv[1]);
    err = NULL;
    CHECK(xn_note_open(bad, work, &host, &err) == NULL, "missing file");
    CHECK(err != NULL && strlen(err) > 0, "an error message");
    xn_free_string(err);
    err = NULL;
    CHECK(xn_note_open(path, work, &host, NULL) != NULL || 1, "a NULL error pointer is allowed");
    xn_editor_touch(NULL, NULL); /* ignored, not a crash */

    /* Timers: whatever the core posted can be run (or has been cancelled) without harm. */
    for (int i = 0; i < hs.delayed_n; i++)
        if (hs.delayed[i]) xn_editor_run_task(ed, hs.delayed[i]);
    for (int i = 0; i < hs.frames_n; i++) xn_editor_run_task(ed, hs.frames[i]);
    xn_editor_run_task(ed, 999999); /* unknown ids are ignored */

    /*
     * Long strokes grow the wet-ink surface (a new one, the old recycled) and zooms rebuild page
     * caches, while the GC collects the dropped surface objects. Released addresses come back
     * from s_create, so a collected object must not release the surface now living there.
     */
    for (int round = 0; round < 12; round++) {
        int64_t t2 = 100000 + round * 10000;
        float y = 150 + round * 50;
        pen(ed, XN_ACTION_DOWN, 100, y, t2, 0, NULL, NULL);
        for (int step = 1; step <= 150; step++) {
            pen(ed, XN_ACTION_MOVE, 100 + step * 4, y + (step % 7), t2 + step * 4, 0, NULL, NULL);
            if (step % 5 == 0) xn_editor_paint(ed, &hs.screen_target);
        }
        pen(ed, XN_ACTION_UP, 700, y, t2 + 604, 0, NULL, NULL);
        xn_editor_zoom_at(ed, 400, 500, round % 2 ? 0.8 : 1.25);
        xn_editor_paint(ed, &hs.screen_target);
        for (int i = 0; i < hs.delayed_n; i++)
            if (hs.delayed[i]) xn_editor_run_task(ed, hs.delayed[i]); /* sharp re-render: new caches */
        hs.delayed_n = 0;
        xn_editor_paint(ed, &hs.screen_target);
        if (round % 3 == 2) { /* rarely, so released addresses are handed out again before it */
            xn_test_collect_garbage();
            usleep(30000);
            xn_editor_paint(ed, &hs.screen_target);
            xn_editor_paint(ed, &hs.screen_target);
        }
    }
    CHECK(reuses > 0, "released addresses were handed out again (%d of %d)", reuses, creates);
    CHECK(stale_uses == 0, "no released surface is used (%d uses)", stale_uses);
    CHECK(double_releases == 0, "no surface is released twice (%d)", double_releases);

    CHECK(hs.surfaces_live > 0, "page caches were live while editing");
    xn_editor_destroy(ed);
    CHECK(hs.surfaces_live == 0, "destroy releases every surface (%d left)", hs.surfaces_live);
    CHECK(stale_uses == 0 && double_releases == 0, "teardown is clean (%d stale, %d double)", stale_uses, double_releases);
    xn_note_close(note);

    /* v2: a note over a PDF. */
    char pdf[1024];
    snprintf(pdf, sizeof pdf, "%s/lecture.pdf", argv[1]);
    FILE* f = fopen(pdf, "wb");
    if (f) {
        fputs("%PDF-1.4 stand-in\n", f);
        fclose(f);
    }
    err = NULL;
    CHECK(xn_note_import_pdf("/no/such.txt", &host, &err) == NULL && err != NULL, "a non-PDF is refused");
    xn_free_string(err);
    err = NULL;
    xn_note* lecture = xn_note_import_pdf(pdf, &host, &err);
    CHECK(lecture != NULL, "import pdf: %s", err ? err : "");
    if (lecture) {
        double box[4];
        CHECK(xn_note_page_count(lecture) == 3, "one page per PDF page");
        CHECK(xn_note_dpi(lecture) == 150, "dpi");
        CHECK(xn_note_page_box(lecture, 0, box) == 1 && box[2] > 1239 && box[2] < 1241 && box[3] > 1754 && box[3] < 1755,
              "A4 at 150 dpi (%g x %g)", box[2], box[3]);
        xn_editor* le = xn_editor_create(lecture, &host);
        xn_editor_set_viewport(le, 800, 1000, 1.0);
        hs.pdf_draws = 0;
        xn_editor_paint(le, &hs.screen_target);
        CHECK(hs.pdf_draws > 0 && strcmp(hs.pdf_path, pdf) == 0, "the PDF is the page background (%d)", hs.pdf_draws);
        xn_editor_destroy(le);

        hs.pdf_draws = 0;
        memset(&hs.screen, 0, sizeof(hs.screen));
        xn_note_paint_page(lecture, 1, &hs.screen_target, &host, 0xFFFFFFFFu, 2.0);
        CHECK(hs.pdf_draws == 1 && hs.screen.fills > 0, "paint_page: paper and PDF (%d)", hs.pdf_draws);

        char saved[1024];
        snprintf(saved, sizeof saved, "%s/lecture.xnote", argv[1]);
        CHECK(xn_note_save(lecture, saved, &host, &err) == 1, "save the PDF note: %s", err ? err : "");
        xn_note_close(lecture);
        char work2[1024];
        snprintf(work2, sizeof work2, "%s", argv[1]);
        xn_note* again = xn_note_open(saved, work2, &host, &err);
        CHECK(again != NULL && xn_note_page_count(again) == 3, "reopened with its pages");
        if (again) {
            hs.pdf_path[0] = 0;
            xn_note_paint_page(again, 0, &hs.screen_target, &host, 0xFFFFFFFFu, 1.0);
            CHECK(is_pdf(hs.pdf_path) && strcmp(hs.pdf_path, pdf) != 0, "the embedded PDF was extracted (%s)", hs.pdf_path);
            xn_note_close(again);
        }
    }

    /* v2: an infinite canvas. */
    {
        xn_note* canvas = xn_canvas_new();
        CHECK(canvas && xn_note_is_canvas(canvas) == 1 && xn_note_is_canvas(note) == 0, "a canvas is a canvas");
        CHECK(xn_note_page_count(canvas) == 0 && xn_note_item_count(canvas, 0) == 0, "no pages, no items");
        xn_editor* ce = xn_editor_create(canvas, &host);
        CHECK(ce != NULL, "canvas editor");
        xn_editor_set_viewport(ce, 800, 600, 1.0);
        CHECK(xn_editor_zoom(ce) == 1.0, "an empty canvas opens at 100%%");
        memset(&hs.screen, 0, sizeof hs.screen);
        memset(&hs.offscreen, 0, sizeof hs.offscreen);
        xn_editor_paint(ce, &hs.screen_target);
        CHECK(hs.offscreen.fills > 0 && hs.screen.surfaces == 1, "paper and ruling, composed and copied out");
        CHECK(xn_editor_set_tool(ce, "text_box") == 0 && xn_editor_set_tool(ce, "pen") == 1, "canvas tools");
        xn_editor_set_ink_color(ce, 0x2050A0FFu);
        int64_t ct = 50000;
        pen(ce, XN_ACTION_DOWN, 100, 300, ct, 0, NULL, NULL);
        xn_editor_paint(ce, &hs.screen_target);
        int partial = 0;
        double dmg2[4];
        for (int i = 1; i <= 60; i++) {
            pen(ce, XN_ACTION_MOVE, 100 + i * 8, 300 + (i % 7) * 3, ct + i * 4, 0, NULL, NULL);
            if (xn_editor_frame_damage(ce, dmg2) && dmg2[2] < 300) partial++;
            xn_editor_paint(ce, &hs.screen_target);
        }
        pen(ce, XN_ACTION_UP, 580, 300, ct + 260, 0, NULL, NULL);
        CHECK(partial > 50, "writing on the canvas repaints around the pen (%d partial frames)", partial);
        CHECK(xn_note_item_count(canvas, 0) == 1 && xn_note_is_dirty(canvas), "a stroke on the canvas");
        CHECK(xn_editor_frame_damage(ce, dmg2) == 0, "filing it repaints in full");
        memset(&hs.screen, 0, sizeof hs.screen);
        memset(&hs.offscreen, 0, sizeof hs.offscreen);
        xn_editor_paint(ce, &hs.screen_target);
        CHECK(hs.screen.surfaces > 0 && hs.offscreen.ribbons > 0, "the ink comes from tiles (%d tiles, %d ribbons)",
              hs.screen.surfaces, hs.offscreen.ribbons);
        /* Panning reuses the tiles in view. */
        memset(&hs.offscreen, 0, sizeof hs.offscreen);
        xn_editor_scroll_by(ce, 40, 0);
        xn_editor_paint(ce, &hs.screen_target);
        CHECK(hs.offscreen.ribbons == 0, "a small pan renders no ink again");

        xn_editor_select_all(ce);
        CHECK(xn_editor_has_selection(ce) == 1, "select all on the canvas");
        double cr[4];
        CHECK(xn_editor_selection_rect(ce, cr) == 1 && cr[2] > 0, "its menu rect");
        xn_editor_duplicate(ce);
        CHECK(xn_note_item_count(canvas, 0) == 2, "duplicated");
        xn_editor_undo(ce);
        CHECK(xn_note_item_count(canvas, 0) == 1, "undo on the canvas");

        xn_editor_set_tool(ce, "eraser");
        xn_editor_set_erase_mode(ce, XN_ERASE_STROKE);
        pen(ce, XN_ACTION_DOWN, 300, 250, ct + 400, 0, NULL, NULL);
        for (int i = 1; i <= 20; i++) pen(ce, XN_ACTION_MOVE, 300, 250 + i * 5, ct + 400 + i * 4, 0, NULL, NULL);
        pen(ce, XN_ACTION_UP, 300, 350, ct + 500, 0, NULL, NULL);
        CHECK(xn_note_item_count(canvas, 0) == 0, "erased on the canvas");
        xn_editor_undo(ce);
        CHECK(xn_note_item_count(canvas, 0) == 1, "and back");

        xn_page_style bgs;
        xn_editor_page_style(ce, 1, &bgs);
        CHECK(bgs.pattern == XN_PATTERN_GRID, "a canvas starts on a grid");
        xn_page_style dots = {1, 0xFFFFFFFFu, XN_PATTERN_DOTS, 1, 0x80808060u, 32};
        xn_editor_set_page_style(ce, 1, &dots);
        xn_editor_page_style(ce, 1, &bgs);
        CHECK(bgs.pattern == XN_PATTERN_DOTS && bgs.spacing == 32 && bgs.page_color == 0xFFFFFFFFu, "canvas background");

        double z = xn_editor_zoom(ce);
        xn_editor_zoom_step(ce, 1);
        CHECK(xn_editor_zoom(ce) > z, "zoom in");
        double sc2[6];
        xn_editor_scroll_info(ce, sc2);
        CHECK(sc2[2] > 0 && sc2[3] > 0 && sc2[4] == 800, "scroll bars over the canvas");
        xn_editor_add_page(ce);           /* page commands do nothing on a canvas */
        xn_editor_go_to_page(ce, 3);
        xn_text_field nofield;
        CHECK(xn_editor_text_field(ce, &nofield) == 0, "no text boxes");

        char cpath[1024];
        snprintf(cpath, sizeof cpath, "%s/board.xcanvas", argv[1]);
        CHECK(xn_note_save(canvas, cpath, &host, &err) == 1, "save the canvas: %s", err ? err : "");
        CHECK(xn_note_is_dirty(canvas) == 0, "saved");
        xn_editor_destroy(ce);
        xn_note_close(canvas);
        xn_note* back2 = xn_note_open(cpath, argv[1], &host, &err);
        CHECK(back2 && xn_note_is_canvas(back2) && xn_note_item_count(back2, 0) == 1, "reopened the canvas");
        if (back2) {
            xn_editor* be = xn_editor_create(back2, &host);
            xn_editor_set_viewport(be, 800, 600, 1.0);
            xn_editor_page_style(be, 1, &bgs);
            CHECK(bgs.pattern == XN_PATTERN_DOTS, "with its background");
            xn_editor_paint(be, &hs.screen_target);
            xn_editor_destroy(be);
            xn_note_close(back2);
        }
        CHECK(hs.surfaces_live == 0, "canvas tiles all released (%d live)", hs.surfaces_live);
    }

    if (failures) {
        fprintf(stderr, "%d failure(s)\n", failures);
        return 1;
    }
    printf("capi_test: all checks passed (screen ribbons %d, cache ribbons %d, surfaces live %d)\n",
           hs.screen.ribbons, hs.offscreen.ribbons, hs.surfaces_live);
    return 0;
}
