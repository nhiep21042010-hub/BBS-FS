package mchorse.bbs_mod.ui.forms;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.categories.FormCategory;
import mchorse.bbs_mod.forms.categories.RecentFormCategory;
import mchorse.bbs_mod.forms.categories.UserFormCategory;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.forms.categories.UIFormCategory;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Overview" layout of the form list, modelled on BBS CML Edition's category cards.
 *
 * <p>Instead of one full-width strip per category, categories are shown as small cards
 * (a 2x2 preview of the first forms and a "+N" count) that wrap in a grid under a heading
 * per group (Recent forms, Categories, Models, Particles...). Clicking a card opens that
 * category as a full-width panel right under the row of cards it sits in - the panel is
 * the ordinary {@link UIFormCategory}, so selecting, dragging and the context menus work
 * exactly as they always did.</p>
 *
 * <p>Only one category is open at a time (opening a card closes the others). While a
 * search is typed the list falls back to its normal layout, so results are not hidden
 * inside closed cards.</p>
 */
public class UIFormOverview
{
    /** Whether new lists start in overview mode. Shared by every list, reset with the game. */
    private static boolean enabled = true;

    public static final int CARD_W = 170;
    public static final int CARD_H = 148;
    public static final int GAP = 8;
    public static final int PAD = 10;
    public static final int CARD_HEADER = 20;
    public static final int HEADING_H = 28;

    private final UIFormList list;

    /* Categories whose thumbnails were switched off with the card's eye (by category id) */
    private final Set<String> hiddenPreviews = new HashSet<>();

    private boolean built;
    private boolean collapsedOnce;
    private String signature = "";

    public UIFormOverview(UIFormList list)
    {
        this.list = list;
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

        this.signature = "";
        this.collapsedOnce = false;
    }

    /* Layout */

    /** Called every frame: rebuilds the layout only when something that shapes it changed. */
    public void sync()
    {
        String current = this.computeSignature();

        if (!current.equals(this.signature))
        {
            this.rebuild();
        }
    }

    private String computeSignature()
    {
        StringBuilder builder = new StringBuilder();

        builder.append(this.isActive() ? 'o' : 'n').append(this.list.forms.area.w / 8).append(':');

        for (UIFormCategory category : this.list.getCategoryUIs())
        {
            builder.append(category.category.visible.get() ? '1' : '0');
        }

        builder.append(':').append(this.list.getCategoryUIs().size());

        return builder.toString();
    }

    /** Lay the categories out again, in the mode the list is in right now. */
    public void rebuild()
    {
        List<UIFormCategory> categories = this.list.getCategoryUIs();
        boolean active = this.isActive();

        if (active && !this.collapsedOnce)
        {
            /* Every panel open at once would bury the cards, so start with all of them closed */
            this.collapsedOnce = true;

            for (UIFormCategory category : categories)
            {
                category.category.visible.set(false);
            }
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

            this.built = true;
            this.list.resize();

            return;
        }

        int width = this.list.forms.area.w;
        int perRow = Math.max(1, (width - PAD * 2 + GAP) / (CARD_W + GAP));

        Map<String, List<UIFormCategory>> groups = new LinkedHashMap<>();

        for (UIFormCategory category : categories)
        {
            category.marginBottom(GAP);
            groups.computeIfAbsent(groupOf(category.category), (k) -> new ArrayList<>()).add(category);
        }

        for (Map.Entry<String, List<UIFormCategory>> group : groups.entrySet())
        {
            UIElement heading = new Heading(group.getKey());

            heading.w(1F).h(HEADING_H);
            this.list.forms.add(heading);

            List<UIFormCategory> cards = group.getValue();

            for (int i = 0; i < cards.size(); i += perRow)
            {
                List<UIFormCategory> row = cards.subList(i, Math.min(cards.size(), i + perRow));
                UIElement rowElement = new CardRow(this, new ArrayList<>(row));

                rowElement.w(1F).h(CARD_H).marginBottom(GAP);
                this.list.forms.add(rowElement);

                for (UIFormCategory card : row)
                {
                    if (card.category.visible.get())
                    {
                        this.list.forms.add(card);
                    }
                }
            }
        }

        UIElement bottom = new UIElement();

        bottom.w(1F).h(20);
        this.list.forms.add(bottom);

        this.built = true;
        this.list.resize();
    }

    /** Open the card's category, closing the others; closes it if it already was the open one. */
    private void toggleOpen(UIFormCategory card)
    {
        boolean open = card.category.visible.get();

        for (UIFormCategory category : this.list.getCategoryUIs())
        {
            category.category.visible.set(false);
        }

        if (!open)
        {
            card.category.visible.set(true);
        }
    }

    private void togglePreview(UIFormCategory card)
    {
        String id = card.category.visible.getId();

        if (!this.hiddenPreviews.remove(id))
        {
            this.hiddenPreviews.add(id);
        }
    }

    private boolean isPreviewHidden(UIFormCategory card)
    {
        return this.hiddenPreviews.contains(card.category.visible.getId());
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

    /** One row of cards. */
    public static class CardRow extends UIElement
    {
        private final UIFormOverview overview;
        private final List<UIFormCategory> cards;

        public CardRow(UIFormOverview overview, List<UIFormCategory> cards)
        {
            this.overview = overview;
            this.cards = cards;
        }

        private int cardX(int index)
        {
            return this.area.x + PAD + index * (CARD_W + GAP);
        }

        @Override
        protected boolean subMouseClicked(UIContext context)
        {
            if (context.mouseButton != 0 || !this.area.isInside(context))
            {
                return false;
            }

            for (int i = 0; i < this.cards.size(); i++)
            {
                int x = this.cardX(i);

                if (context.mouseX < x || context.mouseX >= x + CARD_W || context.mouseY < this.area.y || context.mouseY >= this.area.y + CARD_H)
                {
                    continue;
                }

                UIFormCategory card = this.cards.get(i);

                if (context.mouseX >= x + CARD_W - 22 && context.mouseY < this.area.y + CARD_HEADER)
                {
                    this.overview.togglePreview(card);
                }
                else
                {
                    this.overview.toggleOpen(card);
                }

                return true;
            }

            return false;
        }

        @Override
        public void render(UIContext context)
        {
            UIFormList list = this.overview.list;
            Area window = new Area();

            /* Areas are in the scroll view's content space: keep to what the view shows */
            window.set(list.forms.area.x, list.forms.area.y + (int) list.forms.scroll.getScroll(), list.forms.area.w, list.forms.area.h);

            if (this.area.y + CARD_H >= window.y && this.area.y <= window.ey())
            {
                for (int i = 0; i < this.cards.size(); i++)
                {
                    this.renderCard(context, this.cards.get(i), this.cardX(i), this.area.y);
                }
            }

            super.render(context);
        }

        private void renderCard(UIContext context, UIFormCategory card, int x, int y)
        {
            Batcher2D batcher = context.batcher;
            FontRenderer font = batcher.getFont();
            int primary = BBSSettings.primaryColor.get();
            boolean opened = card.category.visible.get();
            boolean hover = context.mouseX >= x && context.mouseX < x + CARD_W && context.mouseY >= y && context.mouseY < y + CARD_H && this.area.isInside(context);
            boolean previews = !this.overview.isPreviewHidden(card);

            batcher.box(x, y, x + CARD_W, y + CARD_H, opened ? (Colors.A50 | primary) : BBSSettings.color(BBSSettings.chromeSurface(), Colors.A50));
            batcher.outline(x, y, x + CARD_W, y + CARD_H, opened || hover ? (Colors.A100 | primary) : Colors.A50, 1);

            String title = font.limitToWidth(card.category.getProcessedTitle(), CARD_W - 34);

            batcher.textShadow(title, x + 6, y + 6, Colors.WHITE);
            batcher.icon(previews ? Icons.VISIBLE : Icons.INVISIBLE, Colors.WHITE, x + CARD_W - 12, y + CARD_HEADER / 2F, 0.5F, 0.5F);

            if (!previews)
            {
                return;
            }

            List<Form> forms = card.category.getForms();
            int pad = 4;
            int cw = (CARD_W - pad * 2 - pad) / 2;
            int ch = (CARD_H - CARD_HEADER - pad * 3) / 2;

            for (int i = 0; i < Math.min(4, forms.size()); i++)
            {
                int cx = x + pad + (i % 2) * (cw + pad);
                int cy = y + CARD_HEADER + pad + (i / 2) * (ch + pad);

                batcher.box(cx, cy, cx + cw, cy + ch, Colors.A50);
                batcher.clip(cx, cy, cw, ch, context);
                FormUtilsClient.renderPreview(forms.get(i), context, cx, cy, cx + cw, cy + ch);
                batcher.unclip(context);
            }

            if (forms.size() > 4)
            {
                String more = "+" + (forms.size() - 4);

                batcher.textCard(more, x + CARD_W - pad - font.getWidth(more) - 6, y + CARD_H - pad - font.getHeight() - 6, Colors.WHITE, Colors.A75, 2);
            }
        }
    }
}
