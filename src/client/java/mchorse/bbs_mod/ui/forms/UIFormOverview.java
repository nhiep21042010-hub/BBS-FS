package mchorse.bbs_mod.ui.forms;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.forms.categories.FormCategory;
import mchorse.bbs_mod.forms.categories.RecentFormCategory;
import mchorse.bbs_mod.forms.categories.UserFormCategory;
import mchorse.bbs_mod.ui.forms.categories.UIFormCategory;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIStringList;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Sidebar" layout of the form list.
 *
 * <p>A column on the left lists every category by name (Home, Recent forms, Models (car)...),
 * and the space beside it shows either:</p>
 * <ul>
 *   <li><b>Home</b>: one small tile per category - folder icon, name and how many forms it
 *   holds - grouped under headings (Recent forms, Categories, Models, Particles...). Nothing
 *   is rendered inside the tiles, so opening the list is cheap even with thousands of models.</li>
 *   <li><b>A category</b>: the ordinary {@link UIFormCategory} with all its forms. Only now are
 *   the form previews drawn, and only for the category you opened.</li>
 * </ul>
 *
 * <p>Click a tile or a name in the sidebar to open a category, Home to come back. Selecting,
 * dragging and the context menus inside a category work exactly as they always did. While a
 * search is typed the list falls back to its normal layout, so results are not hidden.</p>
 */
public class UIFormOverview
{
    /** Whether new lists start in sidebar mode. Shared by every list, reset with the game. */
    private static boolean enabled = true;

    public static final int SIDEBAR_W = 150;
    public static final int TILE_W = 170;
    public static final int TILE_H = 44;
    public static final int GAP = 8;
    public static final int PAD = 10;
    public static final int HEADING_H = 28;

    private final UIFormList list;
    private final UIStringList sidebar;

    /** The categories the sidebar rows stand for, in row order (row 0 is Home). */
    private List<UIFormCategory> sidebarCategories = new ArrayList<>();

    private boolean attached;

    /** Id of the category the list is showing right now, or null on Home. */
    private String enteredId;

    /** Scroll position of Home, to come back to after visiting a category. */
    private double savedScroll;

    /** A scroll position to apply once the next rebuild has laid everything out. */
    private Double pendingScroll;

    private String signature = "";

    public UIFormOverview(UIFormList list)
    {
        this.list = list;
        this.sidebar = new UIStringList(this::onSidebarPick);
        this.sidebar.background();
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    /** The mode is on, and nothing is being searched. */
    private boolean isActive()
    {
        return enabled && this.list.search.getText().trim().isEmpty();
    }

    public void toggle()
    {
        enabled = !enabled;

        this.enteredId = null;
        this.signature = "";
    }

    /* Navigation. These only note the wish: the layout changes on the next frame, in
     * sync(), so the element tree is never rebuilt in the middle of a click. */

    private void enter(UIFormCategory card)
    {
        if (this.enteredId == null)
        {
            this.savedScroll = this.list.forms.scroll.getScroll();
        }

        this.enteredId = card.category.visible.getId();
        this.pendingScroll = 0D;
        this.signature = "";
    }

    public void leave()
    {
        if (this.enteredId == null)
        {
            return;
        }

        this.enteredId = null;
        this.pendingScroll = this.savedScroll;
        this.signature = "";
    }

    private void onSidebarPick(List<String> picked)
    {
        int index = this.sidebar.getIndex();

        if (index <= 0 || index > this.sidebarCategories.size())
        {
            this.leave();
        }
        else
        {
            this.enter(this.sidebarCategories.get(index - 1));
        }
    }

    private UIFormCategory findEntered(List<UIFormCategory> categories)
    {
        if (this.enteredId == null)
        {
            return null;
        }

        for (UIFormCategory category : categories)
        {
            if (this.enteredId.equals(category.category.visible.getId()))
            {
                return category;
            }
        }

        return null;
    }

    /* Layout */

    private void attach()
    {
        if (this.attached)
        {
            return;
        }

        int top = UIFormList.BAR_HEIGHT + UIFormList.STATUS_HEIGHT;

        this.attached = true;
        this.sidebar.relative(this.list).xy(0, top).w(SIDEBAR_W).h(1F, -top);
        this.list.add(this.sidebar);
    }

    /** Called every frame: rebuilds the layout only when something that shapes it changed. */
    public void sync()
    {
        if (this.isActive() && this.signature.startsWith("o") && this.enteredId != null)
        {
            /* The open category was folded with its own header (or "collapse all"): back to Home */
            UIFormCategory current = this.findEntered(this.list.getCategoryUIs());

            if (current != null && !current.category.visible.get())
            {
                this.leave();
            }
        }

        String current = this.computeSignature();

        if (!current.equals(this.signature))
        {
            this.rebuild();
        }
    }

    private String computeSignature()
    {
        StringBuilder builder = new StringBuilder();
        int titles = 0;

        for (UIFormCategory category : this.list.getCategoryUIs())
        {
            builder.append(category.category.visible.get() ? '1' : '0');
            titles = titles * 31 + category.category.getProcessedTitle().hashCode() + category.category.getForms().size();
        }

        return (this.isActive() ? "o" : "n") + this.list.forms.area.w / 8 + ":" + builder + ":" + this.list.getCategoryUIs().size() + ":" + titles + ":" + this.enteredId;
    }

    /** Lay the categories out again, in the mode the list is in right now. */
    public void rebuild()
    {
        this.attach();

        List<UIFormCategory> categories = this.list.getCategoryUIs();
        boolean active = this.isActive();
        UIFormCategory inside = null;
        int top = UIFormList.BAR_HEIGHT + UIFormList.STATUS_HEIGHT;

        this.sidebar.setVisible(active);
        this.list.forms.xy(active ? SIDEBAR_W : 0, top).w(1F, active ? -SIDEBAR_W : 0);

        if (active)
        {
            inside = this.findEntered(categories);

            if (inside == null)
            {
                /* On Home, or the category is gone (its last model was deleted) */
                this.enteredId = null;
            }

            /* Home shows no panels; a category page has only that one open */
            for (UIFormCategory category : categories)
            {
                category.category.visible.set(category == inside);
            }

            this.fillSidebar(categories, inside);
        }

        this.signature = this.computeSignature();
        this.list.forms.removeAll();

        if (!active)
        {
            for (UIFormCategory category : categories)
            {
                category.marginBottom(0);
                this.list.forms.add(category);
            }

            if (!categories.isEmpty())
            {
                categories.get(categories.size() - 1).marginBottom(20);
            }

            this.finishLayout();

            return;
        }

        if (inside != null)
        {
            inside.marginBottom(20);
            this.list.forms.add(inside);

            this.finishLayout();

            return;
        }

        int width = this.list.forms.area.w;
        int perRow = Math.max(1, (width - PAD * 2 + GAP) / (TILE_W + GAP));

        Map<String, List<UIFormCategory>> groups = new LinkedHashMap<>();

        for (UIFormCategory category : categories)
        {
            groups.computeIfAbsent(groupOf(category.category), (k) -> new ArrayList<>()).add(category);
        }

        for (Map.Entry<String, List<UIFormCategory>> group : groups.entrySet())
        {
            UIElement heading = new Heading(group.getKey());

            heading.w(1F).h(HEADING_H);
            this.list.forms.add(heading);

            List<UIFormCategory> tiles = group.getValue();

            for (int i = 0; i < tiles.size(); i += perRow)
            {
                List<UIFormCategory> row = tiles.subList(i, Math.min(tiles.size(), i + perRow));
                UIElement rowElement = new TileRow(this, new ArrayList<>(row));

                rowElement.w(1F).h(TILE_H).marginBottom(GAP);
                this.list.forms.add(rowElement);
            }
        }

        UIElement bottom = new UIElement();

        bottom.w(1F).h(20);
        this.list.forms.add(bottom);

        this.finishLayout();
    }

    private void fillSidebar(List<UIFormCategory> categories, UIFormCategory inside)
    {
        List<String> titles = new ArrayList<>();
        double scroll = this.sidebar.scroll.getScroll();

        titles.add("Home");

        for (UIFormCategory category : categories)
        {
            titles.add(category.category.getProcessedTitle());
        }

        this.sidebarCategories = new ArrayList<>(categories);
        this.sidebar.clear();
        this.sidebar.add(titles);
        this.sidebar.setIndex(inside == null ? 0 : categories.indexOf(inside) + 1);
        this.sidebar.scroll.setScroll(scroll);
    }

    private void finishLayout()
    {
        this.list.resize();

        if (this.pendingScroll != null)
        {
            this.list.forms.scroll.setScroll(this.pendingScroll);
            this.pendingScroll = null;
        }
    }

    /** The heading a category sits under: user categories together, the rest by their title. */
    private static String groupOf(FormCategory category)
    {
        if (category instanceof RecentFormCategory)
        {
            return "Recent forms";
        }

        if (category instanceof UserFormCategory)
        {
            return "Categories";
        }

        String title = category.getProcessedTitle();
        int paren = title.indexOf(" (");

        return paren > 0 ? title.substring(0, paren) : title;
    }

    /* Elements */

    /** A group's title with a thin line under it. */
    public static class Heading extends UIElement
    {
        private final String title;

        public Heading(String title)
        {
            this.title = title;
        }

        @Override
        public void render(UIContext context)
        {
            Batcher2D batcher = context.batcher;

            batcher.text(this.title, this.area.x + PAD, this.area.y + 14, Colors.GRAY);
            batcher.box(this.area.x, this.area.ey() - 1, this.area.ex(), this.area.ey(), Colors.A12 | 0xffffff);

            super.render(context);
        }
    }

    /** One row of folder tiles. Nothing inside a tile is rendered from the forms themselves. */
    public static class TileRow extends UIElement
    {
        private final UIFormOverview overview;
        private final List<UIFormCategory> tiles;

        public TileRow(UIFormOverview overview, List<UIFormCategory> tiles)
        {
            this.overview = overview;
            this.tiles = tiles;
        }

        private int tileX(int index)
        {
            return this.area.x + PAD + index * (TILE_W + GAP);
        }

        private boolean isOver(UIContext context, int x)
        {
            return context.mouseX >= x && context.mouseX < x + TILE_W && context.mouseY >= this.area.y && context.mouseY < this.area.y + TILE_H;
        }

        @Override
        protected boolean subMouseClicked(UIContext context)
        {
            if (context.mouseButton != 0 || !this.area.isInside(context))
            {
                return false;
            }

            for (int i = 0; i < this.tiles.size(); i++)
            {
                if (this.isOver(context, this.tileX(i)))
                {
                    this.overview.enter(this.tiles.get(i));

                    return true;
                }
            }

            return false;
        }

        @Override
        public void render(UIContext context)
        {
            Batcher2D batcher = context.batcher;
            FontRenderer font = batcher.getFont();
            int primary = BBSSettings.primaryColor.get();

            for (int i = 0; i < this.tiles.size(); i++)
            {
                UIFormCategory tile = this.tiles.get(i);
                int x = this.tileX(i);
                int y = this.area.y;
                boolean hover = this.area.isInside(context) && this.isOver(context, x);

                batcher.box(x, y, x + TILE_W, y + TILE_H, BBSSettings.color(BBSSettings.chromeSurface(), Colors.A50));
                batcher.outline(x, y, x + TILE_W, y + TILE_H, hover ? (Colors.A100 | primary) : Colors.A50, 1);
                batcher.icon(Icons.FOLDER, Colors.WHITE, x + 22, y + TILE_H / 2F, 0.5F, 0.5F);

                String title = font.limitToWidth(tile.category.getProcessedTitle(), TILE_W - 50);
                int count = tile.category.getForms().size();

                batcher.textShadow(title, x + 42, y + 9, Colors.WHITE);
                batcher.text(count + (count == 1 ? " form" : " forms"), x + 42, y + 9 + font.getHeight() + 4, Colors.GRAY);
            }

            super.render(context);
        }
    }
}
