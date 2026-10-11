/*
 * xnotes core — C API (v3).
 *
 * The Kotlin/Native build of the xnotes core (libxnotes.so) behind a small C interface for native
 * hosts such as the Qt application. The core keeps the document model, file formats, the paged
 * canvas (layout, page caches, frame composition) and pen/touch interaction; the host supplies
 * drawing, text measurement, image probing and timers through the callbacks in xn_host, and feeds
 * it input.
 *
 * Conventions
 *  - Every call is made on the host's UI thread. The core calls back on that thread, from inside the
 *    API call that caused it — except with xn_host.wake set (v3), where page caches are drawn on
 *    threads of the core's own; see there.
 *  - Colours are 0xRRGGBBAA. Text is UTF-16 (the core's and QString's unit), with explicit lengths.
 *  - Coordinates handed to the renderer are in the renderer's current transform; the core sets up
 *    zoom and scroll itself with translate/scale.
 *  - Handles are owned by whoever created them and freed with the matching *_destroy/_close call.
 *    Strings the core returns are freed with xn_free_string.
 */
#ifndef XNOTES_H
#define XNOTES_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define XN_API_VERSION 3

typedef uint32_t xn_rgba; /* 0xRRGGBBAA */

typedef struct xn_note xn_note;     /* an open .xnote document */
typedef struct xn_editor xn_editor; /* a paged-canvas editor over a note */

/* --- drawing -------------------------------------------------------------------------------- */

enum { XN_BLEND_SRC_OVER = 0, XN_BLEND_MULTIPLY = 1, XN_BLEND_SCREEN = 2 };
enum { XN_FILL_NONZERO = 0, XN_FILL_EVEN_ODD = 1 };

typedef struct xn_pen {
    xn_rgba color;
    double width;
    int cosmetic;       /* width in device pixels, unaffected by the transform */
    int dashed;
    double dash_on, dash_gap, dash_phase;
} xn_pen;

typedef struct xn_font {
    double point_size;
    const uint16_t* face; /* family id, UTF-16 */
    int face_len;
    int bold, italic;
} xn_font;

/*
 * A drawing target. `r` is the host's own pointer: the one passed to xn_editor_paint, or one a
 * surface handed out through xn_host.surface_renderer. Every function is required.
 */
typedef struct xn_renderer_vt {
    void (*save)(void* r);
    void (*restore)(void* r);
    /* Offscreen layer over the rect, composited at `alpha` with `blend` on the matching restore. */
    void (*save_layer)(void* r, double x, double y, double w, double h, double alpha, int blend);
    void (*translate)(void* r, double dx, double dy);
    void (*scale)(void* r, double sx, double sy);
    void (*rotate)(void* r, double degrees); /* multiples of 90 */
    void (*clip_rect)(void* r, double x, double y, double w, double h);
    void (*clear)(void* r); /* clear the current clip to transparent */

    void (*fill_rect)(void* r, double x, double y, double w, double h, xn_rgba c);
    void (*fill_polygon)(void* r, const double* xy, int n, xn_rgba c, int fill_rule);
    void (*fill_ellipse)(void* r, double cx, double cy, double rx, double ry, xn_rgba c);
    /*
     * Ink: a disc of radius radii[i] at every centre (x, y interleaved) from `from` for `count`
     * points, plus the hull bridging consecutive discs, all in one colour. The hot path while
     * writing; filling it as one path keeps shared edges from seaming.
     */
    void (*fill_disk_ribbon)(void* r, const float* centers, const float* radii, int from, int count, xn_rgba c);

    void (*stroke_polyline)(void* r, const double* xy, int n, int closed, const xn_pen* pen);
    void (*stroke_rect)(void* r, double x, double y, double w, double h, const xn_pen* pen);
    void (*stroke_ellipse)(void* r, double cx, double cy, double rx, double ry, const xn_pen* pen);

    /* A surface (see xn_host.surface_create) scaled into dest; src may be NULL for the whole. */
    void (*draw_surface)(void* r, void* surface, const double* dest_xywh, const double* src_xywh,
                         double alpha, int blend);
    /* An image file (UTF-8 path) into dest, turned `orientation` degrees then `angle` radians about dest's centre. */
    void (*draw_image)(void* r, const char* path, double x, double y, double w, double h,
                       int orientation, double angle);
    void (*draw_text)(void* r, const uint16_t* text, int len, double x, double y, double w, double h,
                      const xn_font* font, xn_rgba c, int word_wrap, int align_left, int align_top);
    void (*draw_text_run)(void* r, const uint16_t* text, int len, double x, double baseline,
                          const xn_font* font, xn_rgba c);
} xn_renderer_vt;

/* --- host services ------------------------------------------------------------------------- */

enum { XN_NOTICE_KEEP_ONE_PAGE = 1, XN_NOTICE_PAGE_ALREADY_EMPTY = 2 };

struct xn_text_field;

typedef struct xn_host {
    void* ctx; /* passed back as the first argument of every service below */
    const xn_renderer_vt* renderer;

    /* Offscreen surfaces the core caches pages in (e.g. a QImage, transparent when created). */
    void* (*surface_create)(void* ctx, int width, int height, double device_pixel_ratio);
    void (*surface_fill)(void* ctx, void* surface, xn_rgba c);
    /* A renderer drawing into the surface, with an identity transform and no clip, fresh per call.
       It stays valid until the surface is released, and the core may keep drawing into it after
       the surface was drawn or filled (the wet-ink cache adds to its surface every frame): a host
       that has to finish drawing before reading the pixels (QPainter::end) resumes on the next
       call through the renderer. */
    void* (*surface_renderer)(void* ctx, void* surface);
    void (*surface_release)(void* ctx, void* surface);

    /* Text measurement, in page pixels (fonts are sized in points at 150 px per inch). */
    void (*text_measure)(void* ctx, const uint16_t* text, int len, const xn_font* font, double wrap_width,
                         int word_wrap, double* out_xywh);
    void (*text_metrics)(void* ctx, const xn_font* font, double* ascent, double* descent);
    /* The advance of each UTF-16 unit of text (len values into out). */
    void (*text_advances)(void* ctx, const uint16_t* text, int len, const xn_font* font, double* out);

    /* An image file's pixel size without decoding it; returns 0 when it cannot be read. */
    int (*image_probe)(void* ctx, const char* path, int* width, int* height);

    /* Something changed on screen: schedule a repaint (xn_editor_paint). */
    void (*request_render)(void* ctx);
    /* The document or the undo stack changed (refresh undo buttons, dirty marker, page count). */
    void (*content_changed)(void* ctx);
    /* Zoom, scroll or the current page changed. */
    void (*view_changed)(void* ctx);
    void (*notice)(void* ctx, int code);

    /* Timers. The host calls xn_editor_run_task(editor, task) when one fires. */
    void (*post_delayed)(void* ctx, uint64_t task, int64_t delay_ms);
    void (*cancel_task)(void* ctx, uint64_t task);
    /* Run the task on the next display frame (animations: fling, fade). */
    void (*post_frame)(void* ctx, uint64_t task);

    /* --- v2. Each of these may be NULL. --- */

    /* A selection settled (shown = 1, with its viewport rect, for a floating action bar next to
       it) or went away (shown = 0). Only its position changes while the view scrolls; see
       xn_editor_selection_rect. */
    void (*selection_menu)(void* ctx, int shown, double x, double y, double w, double h);
    /* A long press on empty space, or on a locked item (on_locked = 1): offer a context menu at
       the viewport point (paste; unlock with xn_editor_unlock_pressed). */
    void (*context_menu)(void* ctx, double x, double y, int on_locked);
    /* The editor switched tools by itself (a long-press grab, and back). */
    void (*tool_changed)(void* ctx, const char* tool_id);
    /* A text box is being edited: show an input field there (field != NULL, its strings valid
       during the call; sent again when its style changes), or close it (field == NULL). While
       open, the box is not drawn on the canvas: the field shows it. */
    void (*text_edit)(void* ctx, const struct xn_text_field* field);

    /* PDF (UTF-8 paths). A page count below 0 means the file cannot be read. */
    int (*pdf_page_count)(void* ctx, const char* path);
    /* A page's size in points; 0 when it cannot be read. */
    int (*pdf_page_size)(void* ctx, const char* path, int page, double* width_pt, double* height_pt);
    /*
     * Draw part of a PDF page as a note page's background: the page rendered at full_w x full_h
     * pixels, of which the pixel rect (rx, ry, rw, rh) goes into the renderer's rect
     * (x, y, w, h) under its current transform. Returns 1 when that was the exact render, 0 when
     * a stand-in (say a render at another size, scaled) while the exact one is made; the host
     * then calls xn_editor_refresh_backgrounds once it has it.
     */
    int (*draw_pdf_page)(void* ctx, void* renderer, const char* path, int page, int full_w, int full_h,
                         int rx, int ry, int rw, int rh, double x, double y, double w, double h);

    /* --- v3. May be NULL. --- */

    /*
     * Background drawing. With wake set, an editor draws on worker threads of its own: a paged one
     * its page caches (ink and backgrounds) and the sharp view, a canvas the tiles a pan or a zoom
     * uncovers (and those around the view, ahead of a pan). The UI thread never waits for them: a
     * frame shows what is ready, and the rest appears as it lands. The host's renderer
     * vtable, surface_create, surface_fill, surface_renderer, text_measure, text_metrics,
     * text_advances, image_probe and draw_pdf_page may then be called from those threads,
     * concurrently with each other and with the UI thread (each surface is used by one thread at a
     * time), and must be thread-safe. Every other callback stays on the UI thread.
     *
     * wake is called from a worker thread when finished work waits for the UI thread: the host
     * then calls xn_editor_take_results there, soon (a queued call, not from inside wake).
     */
    void (*wake)(void* ctx);
} xn_host;

/* --- library -------------------------------------------------------------------------------- */

int xn_api_version(void);
void xn_free_string(char* s);

/* --- documents ----------------------------------------------------------------------------- */

/*
 * Open an .xnote. Embedded images and the source PDF are extracted into work_dir, which must exist
 * and should stay until the note is closed. On failure returns NULL and, when error is not NULL,
 * sets *error to a message (free with xn_free_string).
 */
xn_note* xn_note_open(const char* path, const char* work_dir, const xn_host* host, char** error);

/* A new note of `pages` blank A4 pages. */
xn_note* xn_note_new(int pages);

/*
 * Infinite canvases (.xcanvas) are notes too: xn_note_open reads one by its extension, and an
 * editor over one draws, erases, selects, zooms and scrolls through the same calls. It has no
 * pages: page counts are 0, xn_note_item_count gives its items for any page, page commands and
 * text boxes do nothing, and the page style is the canvas background (paper, ruling, spacing).
 */
xn_note* xn_canvas_new(void);
int xn_note_is_canvas(const xn_note* note);

/*
 * A new note over a PDF: one page per PDF page, each showing it as a background (needs the host's
 * pdf_page_count and pdf_page_size). The note keeps referring to pdf_path until it is closed and
 * embeds the file when saved, so the host copies it somewhere that lives as long.
 */
xn_note* xn_note_import_pdf(const char* pdf_path, const xn_host* host, char** error);

/* Save to path: written to a temp file beside it, flushed to disk, then renamed over it. 1 on success. */
int xn_note_save(xn_note* note, const char* path, const xn_host* host, char** error);

int xn_note_page_count(const xn_note* note);
/* Pixels per inch of the note's page coordinates (150 for notes made by xnotes). */
int xn_note_dpi(const xn_note* note);
/* The page's whole paper (margins included) in page pixels: x and y are 0 or negative. */
int xn_note_page_box(const xn_note* note, int page, double* out_xywh);
/*
 * Draw a page for export or printing into renderer, in page pixels: paper (the page's own colour,
 * else paper), the PDF background rendered at pdf_scale device pixels per page pixel, the ruling,
 * and the items. The caller translates the renderer so the page box lands where it wants it.
 */
void xn_note_paint_page(xn_note* note, int page, void* renderer, const xn_host* host, xn_rgba paper,
                        double pdf_scale);
int xn_note_item_count(const xn_note* note, int page);
int xn_note_is_dirty(const xn_note* note);
void xn_note_close(xn_note* note);

/* --- editor --------------------------------------------------------------------------------- */

/* An editor over note (which must outlive it). host is copied; its ctx must outlive the editor. */
xn_editor* xn_editor_create(xn_note* note, const xn_host* host);
void xn_editor_destroy(xn_editor* editor);

/* The view's size in device pixels, and device pixels per density-independent pixel. */
void xn_editor_set_viewport(xn_editor* editor, int width, int height, double device_px_per_dp);

/* Paint a frame into renderer (the host's target for host->renderer). */
void xn_editor_paint(xn_editor* editor, void* renderer);

/*
 * Whether the next frame may repaint only part of the view: 1 with that viewport rect when the
 * only change since the last xn_editor_paint is a growing stroke (what writing does on every pen
 * sample), so a host that kept the last frame can clip the next one to it; 0 for a full frame.
 * Ask right before painting; the paint resets it.
 */
int xn_editor_frame_damage(const xn_editor* editor, double* out_xywh);

/* A task posted through post_delayed/post_frame is due. */
void xn_editor_run_task(xn_editor* editor, uint64_t task);

/* v3: take in the work the background threads finished (after xn_host.wake). */
void xn_editor_take_results(xn_editor* editor);
/* v3: background builds running or waiting, and results not taken in yet (0 without wake). */
int xn_editor_background_pending(const xn_editor* editor);

/* --- input --- */

enum {
    XN_ACTION_DOWN = 0, XN_ACTION_UP = 1, XN_ACTION_MOVE = 2, XN_ACTION_CANCEL = 3,
    XN_ACTION_POINTER_DOWN = 5, XN_ACTION_POINTER_UP = 6,
    XN_ACTION_HOVER_MOVE = 7, XN_ACTION_HOVER_ENTER = 9, XN_ACTION_HOVER_EXIT = 10,
};
enum { XN_TOOL_TYPE_UNKNOWN = 0, XN_TOOL_TYPE_FINGER = 1, XN_TOOL_TYPE_STYLUS = 2, XN_TOOL_TYPE_MOUSE = 3, XN_TOOL_TYPE_ERASER = 4 };
enum { XN_BUTTON_STYLUS_PRIMARY = 32, XN_BUTTON_STYLUS_SECONDARY = 64 };

typedef struct xn_pointer {
    int id;
    int tool_type;
    float x, y;     /* viewport device pixels */
    float pressure; /* 0..1 */
} xn_pointer;

/*
 * One input event, shaped like Android's MotionEvent. history holds history_size earlier samples
 * coalesced into this event, oldest first: history_size * pointer_count pointers, sample-major,
 * with their times in history_time_ms. Tablets deliver many; pass them all for smooth ink.
 */
typedef struct xn_pointer_event {
    int action;
    int action_index; /* for POINTER_DOWN/UP */
    int button_state;
    int64_t time_ms;  /* monotonic */
    int pointer_count;
    const xn_pointer* pointers;
    int history_size;
    const int64_t* history_time_ms;
    const xn_pointer* history;
} xn_pointer_event;

void xn_editor_touch(xn_editor* editor, const xn_pointer_event* event);
void xn_editor_hover(xn_editor* editor, const xn_pointer_event* event);

/* --- tools and commands --- */

/* Tool ids as in the file format: "pen", "highlighter", "eraser", "pan", "lasso", "select", ... Returns 0 if unknown. */
int xn_editor_set_tool(xn_editor* editor, const char* tool_id);
/* The ink of new strokes, shapes and boxes; also recolours the text box being edited or selected. */
void xn_editor_set_ink_color(xn_editor* editor, xn_rgba color);
void xn_editor_undo(xn_editor* editor);
void xn_editor_redo(xn_editor* editor);
int xn_editor_can_undo(const xn_editor* editor);
int xn_editor_can_redo(const xn_editor* editor);
void xn_editor_escape(xn_editor* editor);
void xn_editor_delete_selection(xn_editor* editor);

/* The armed tool's id (a static string). */
const char* xn_editor_tool(const xn_editor* editor);

/* Base width of a drawing tool's ink ("pen", "highlighter", ...), in page pixels. */
double xn_editor_tool_width(const xn_editor* editor, const char* tool_id);
void xn_editor_set_tool_width(xn_editor* editor, const char* tool_id, double width);

/*
 * Pens. Each stroke tool is a kind of pen: "pen" (plain), "dashed", "calligraphy" (thick and thin
 * by direction, like a broad nib), "speed" (thins as it moves faster), "taper" (eases to a point
 * at the end) and "highlighter". Each keeps its own style.
 */
typedef struct xn_pen_style {
    int pressure;       /* the width follows the pen's pressure (pen, calligraphy, speed, taper) */
    double sensitivity; /* 0..100: how much thinner a light touch draws, with pressure */
    double multiplier;  /* calligraphy: thickest to thinnest, 1..5 */
    double speed;       /* speed pen: 0..100, how much speed thins the line */
    double tip;         /* taper pen: 0..100, the tip's width in percent of the full width */
    double dash, gap;   /* dashed pen: dash and gap lengths, 2..40 page pixels */
} xn_pen_style;
/* A stroke tool's style; 0 for a tool that is not one. */
int xn_editor_pen_style(const xn_editor* editor, const char* tool_id, xn_pen_style* out);
/* Set it for the strokes drawn from now on (existing ones keep theirs); values are clamped. */
void xn_editor_set_pen_style(xn_editor* editor, const char* tool_id, const xn_pen_style* style);

/* The eraser removes whole strokes it touches, or only the ink under it. Its size is its radius
   in page pixels, set as its width (1..80, 24 by default). */
enum { XN_ERASE_STROKE = 0, XN_ERASE_AREA = 1 };
int xn_editor_erase_mode(const xn_editor* editor);
void xn_editor_set_erase_mode(xn_editor* editor, int mode);

/* What the "shape" tool draws. kind: "line", "arrow", "rectangle", "ellipse", "circle", "triangle". */
typedef struct xn_shape_style {
    const char* kind;
    double width;      /* outline, page pixels */
    int fill;          /* closed shapes: fill with the ink colour at fill_alpha */
    double fill_alpha; /* 0.05..1 */
    int dashed;
} xn_shape_style;
void xn_editor_set_shape_style(xn_editor* editor, const xn_shape_style* style);

/* --- text boxes (tool "text_box") --- */

/* The open text field: (x, y) is its top-left in viewport pixels; width, height and font_px are
   page pixels, drawn at zoom (so on screen the box is width * zoom wide). */
typedef struct xn_text_field {
    double x, y;
    double width, height;
    double font_px;
    double zoom;
    xn_rgba color;
    double rotation;       /* degrees clockwise about (x, y): the view's rotation, plus a shape label's turn */
    const uint16_t* face;  /* font family id, as in xn_font */
    int face_len;
    const uint16_t* text;
    int text_len;
    int centred;           /* v3: every line centred (a shape's label) */
} xn_text_field;

/* The open field's current geometry (after a scroll or zoom); strings are left NULL. 0 when none. */
int xn_editor_text_field(const xn_editor* editor, xn_text_field* out);
/* The field's text changed: keep the box in step (it grows as it fills). */
void xn_editor_text_update(xn_editor* editor, const uint16_t* text, int len);
/* Finish the edit: an empty box is dropped, a changed one recorded for undo. */
void xn_editor_text_commit(xn_editor* editor);
/* Style of the box being edited or the single selected one, and of the next new box.
   face: "mono", "sans", "serif", "hand" or a family name; size in points (6..96). */
void xn_editor_set_text_face(xn_editor* editor, const char* face);
void xn_editor_set_text_size(xn_editor* editor, double point_size);

/*
 * The host now has the exact PDF renders it stood in for (draw_pdf_page returned 0): redraw the
 * backgrounds that got a stand-in, and only those.
 */
void xn_editor_refresh_backgrounds(xn_editor* editor);

/* --- images --- */

/*
 * Put an image file (UTF-8 path; any format the host's image_probe and draw_image read) on a
 * page: at most 60% of the page, centred on the viewport point when at_point, else on the current
 * page. The core keeps referring to the file until the note is closed (saving embeds its bytes),
 * so the host copies it somewhere that lives as long, such as the note's work_dir. Returns 0 when
 * the image cannot be read.
 */
int xn_editor_insert_image(xn_editor* editor, const char* path, int at_point, double x, double y);

/* --- selection (lasso, select, long-press grab) --- */

int xn_editor_has_selection(const xn_editor* editor);
/* The selection's current viewport rect (x, y, w, h) for anchoring its menu; 0 when none. */
int xn_editor_selection_rect(const xn_editor* editor, double* out_xywh);
void xn_editor_select_all(xn_editor* editor);
void xn_editor_cut(xn_editor* editor);
void xn_editor_copy(xn_editor* editor);
void xn_editor_duplicate(xn_editor* editor);
void xn_editor_bring_to_front(xn_editor* editor);
/* Pin the selection in place (it can no longer be selected) and deselect it. */
void xn_editor_lock_selection(xn_editor* editor);
/* Release the locked item the last context_menu (on_locked = 1) was opened for. */
void xn_editor_unlock_pressed(xn_editor* editor);
/* Whether copied items wait to be pasted (the core's own clipboard, within this editor). */
int xn_editor_can_paste(const xn_editor* editor);
/* Paste the copied items with their top-left at the viewport point, and select them. */
void xn_editor_paste_at(xn_editor* editor, double x, double y);

/* Width range of a restyle, in page pixels. */
#define XN_STYLE_MIN_WIDTH 1.0
#define XN_STYLE_MAX_WIDTH 80.0
/*
 * The colour and width the selection's strokes and shapes share (else the first one's). Returns
 * how many selected items carry such a style: 0 when nothing selected can be restyled.
 */
int xn_editor_selection_style(const xn_editor* editor, xn_rgba* color, double* width);
/*
 * Recolour (set_color) and/or re-thicken (set_width) the selection. A preview call skips history;
 * the next call without it records everything since as one undo step (a slider drag), and a call
 * with neither set and preview = 0 just settles a pending preview.
 */
void xn_editor_restyle_selection(xn_editor* editor, int set_color, xn_rgba color, int set_width, double width,
                                 int preview);

/* --- view --- */

void xn_editor_zoom_step(xn_editor* editor, int zoom_in);
/* Zoom about a viewport point, to zoom * factor. */
void xn_editor_zoom_at(xn_editor* editor, double x, double y, double factor);
void xn_editor_scroll_by(xn_editor* editor, double dx, double dy);
void xn_editor_fit_width(xn_editor* editor);
/* For scroll bars, in viewport device pixels: scroll x and y, their maxima, viewport width and height. */
void xn_editor_scroll_info(const xn_editor* editor, double* out6);
/* Scroll to (x, y), clamped as scroll_by is. */
void xn_editor_scroll_to(xn_editor* editor, double x, double y);
void xn_editor_go_to_page(xn_editor* editor, int index);
int xn_editor_current_page(const xn_editor* editor);
double xn_editor_zoom(const xn_editor* editor);

/* Canvas colours: gap, paper, page border, accent, dim text; chrome: panel, border, text, menu. */
typedef struct xn_palette {
    xn_rgba bg, paper, paper_border, accent, text_dim;
    xn_rgba panel, border, text, menu_bg;
    int is_dark;
} xn_palette;
void xn_editor_set_palette(xn_editor* editor, const xn_palette* palette);

/* --- page style: paper colour and ruling --- */

enum { XN_PATTERN_INHERIT = -1, XN_PATTERN_NONE = 0, XN_PATTERN_LINES = 1, XN_PATTERN_DOTS = 2, XN_PATTERN_GRID = 3 };

/* Ruling period range, in page pixels (64 by default, about 10.8 mm at 150 dpi). */
#define XN_PATTERN_MIN_SPACING 16.0
#define XN_PATTERN_MAX_SPACING 200.0

/*
 * A page style override. Each field may inherit (has_* = 0, pattern = XN_PATTERN_INHERIT,
 * spacing <= 0): the current page from the whole note, the note from the defaults (theme
 * paper, no ruling, translucent grey lines 64 px apart).
 */
typedef struct xn_page_style {
    int has_page_color;
    xn_rgba page_color;
    int pattern;
    int has_pattern_color;
    xn_rgba pattern_color; /* its alpha is the ruling's opacity */
    double spacing;
} xn_page_style;

/* The override of all pages (all_pages = 1) or of the current page. */
void xn_editor_page_style(const xn_editor* editor, int all_pages, xn_page_style* out);
/* Replace it; the canvas redraws, the note is marked changed (a style is not an undo step). */
void xn_editor_set_page_style(xn_editor* editor, int all_pages, const xn_page_style* style);

/* --- pages --- */

void xn_editor_add_page(xn_editor* editor);
void xn_editor_delete_current_page(xn_editor* editor);

/*
 * The pages panel. Indices are page positions now; lists of them may be in any order and repeat.
 * Every edit is one undo step, and none leaves the note without a page (notice KEEP_ONE_PAGE).
 */

/* A page's identity, kept while it is moved around and for the note's life (0 when out of range):
   what a host keys page thumbnails by. */
int64_t xn_note_page_id(const xn_note* note, int page);
/* A blank page after index (-1: before the first), sized as that page; the view goes to it. */
void xn_editor_insert_page_after(xn_editor* editor, int index);
void xn_editor_delete_pages(xn_editor* editor, const int* pages, int count);
/* Remove everything on the page but keep it and its background (notice PAGE_ALREADY_EMPTY). */
void xn_editor_erase_page(xn_editor* editor, int index);
/* Copy pages to the editor's page clipboard; cut also deletes them. */
void xn_editor_copy_pages(xn_editor* editor, const int* pages, int count);
void xn_editor_cut_pages(xn_editor* editor, const int* pages, int count);
int xn_editor_can_paste_pages(const xn_editor* editor);
/* Fresh copies of the copied pages after index (-1: at the start); the view goes to the first. */
void xn_editor_paste_pages_after(xn_editor* editor, int index);

#ifdef __cplusplus
}
#endif

#endif /* XNOTES_H */
