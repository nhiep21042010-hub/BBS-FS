package com.bbsvfx.vfxlights.client;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.api.client.events.L10nReloadEvent;
import mchorse.bbs_mod.api.Subscribe;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.l10n.keys.LangKey;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every word the light editors say, in one table, with its translations.
 *
 * <p><b>How this reaches BBS's language switcher.</b> BBS resolves UI text through {@link L10n}: a key
 * is a {@link LangKey} object held in one map, and switching the language reloads the CONTENT of those
 * same objects rather than replacing them ({@code L10n.load} mutates in place). So a static
 * {@code IKey} taken here stays valid forever, and all a switch has to do is rewrite its content —
 * which is what {@link #apply} does on every {@link L10nReloadEvent}, exactly like the BBS VFX hub does
 * for its settings group. No lang files to ship and nothing of BBS's own to touch.</p>
 *
 * <p>A key loaded from an actual language file wins: {@code apply} skips any key that has an origin, so
 * a resource pack (or a community translation) can override these without being clobbered on the next
 * language switch. The keys also show up in BBS's own in-game language editor, since asking for one
 * marks it as requested.</p>
 *
 * <p>Taking the keys in a static initialiser is safe because this addon {@code depends} on bbs: Fabric
 * runs a dependency's client entrypoint first, and BBS builds its {@link L10n} at the top of that.</p>
 */
public final class LightKeys
{
    private static final String PREFIX = "vfxlights.ui.";

    /** Key id → {english, russian}, filled by the constants below in declaration order. */
    private static final Map<String, String[]> STRINGS = new LinkedHashMap<>();

    /** Keys created before BBS's L10n existed (client-init race) — apply() rewrites their content. */
    private static final Map<String, LangKey> FALLBACKS = new LinkedHashMap<>();

    /* Editors and tabs */

    public static final IKey POINT_LIGHT = key("point_light", "Point light", "Точечный свет");
    public static final IKey SPOT_LIGHT = key("spot_light", "Spot light", "Прожектор");
    public static final IKey AREA_LIGHT = key("area_light", "Area light", "Площадной свет");
    public static final IKey AMBIENT_LIGHT = key("ambient_light", "Ambient light", "Окружающий свет");
    public static final IKey TAB_AIR = key("tab.air", "Air", "Воздух");
    public static final IKey TAB_STYLE = key("tab.style", "Style", "Стиль");

    /* Light tab */

    public static final IKey ADVANCED = key("advanced", "Advanced dials", "Все параметры");
    public static final IKey ADVANCED_TOOLTIP = key("advanced_tooltip",
        "Show the rarely-touched dials on every tab of this light",
        "Показывать редкие параметры на всех вкладках этого источника");

    public static final IKey SECTION_LOOK = key("section.look", "Look", "Пресет");
    public static final IKey PRESET_CUSTOM = key("preset.custom", "Custom", "Свой");
    public static final IKey PRESET_TOOLTIP = key("preset_tooltip",
        "A one-click starting point; tweak from there. The name keeps a * once you do",
        "Готовая отправная точка в один клик; дальше крутите сами. После правки к названию добавится *");

    public static final IKey SECTION_LIGHT = key("section.light", "Light", "Свет");
    public static final IKey COLOUR = key("colour", "Colour", "Цвет");
    public static final IKey COLOUR_TOOLTIP = key("colour_tooltip",
        "Emitted colour; with temperature on it tints the blackbody colour instead",
        "Цвет свечения; при включённой температуре — оттеняет цвет по Кельвину");
    public static final IKey INTENSITY = key("intensity", "Intensity", "Яркость");
    public static final IKey INTENSITY_TOOLTIP = key("intensity_tooltip",
        "Brightness. 1.5 reads as an ordinary lamp; a searchlight wants much more",
        "Сила света. 1.5 — обычная лампа; прожектору нужно куда больше");
    public static final IKey RANGE = key("range", "Range", "Дальность");
    public static final IKey RANGE_TOOLTIP = key("range_tooltip",
        "How far the light reaches, in blocks; beyond it the lamp is skipped entirely",
        "Насколько далеко бьёт свет, в блоках; дальше лампа не считается вовсе");
    public static final IKey FALLOFF = key("falloff", "Realistic falloff", "Реалистичное затухание");
    public static final IKey FALLOFF_TOOLTIP = key("falloff_tooltip",
        "Inverse-square 1/d² like a real lamp (the Blender look); wants range >= 10 to keep the window invisible",
        "Обратный квадрат 1/d², как у настоящей лампы (вид Blender); дальность от 10, иначе виден край");
    public static final IKey FLICKER = key("flicker", "Flicker", "Мерцание");
    public static final IKey FLICKER_TOOLTIP = key("flicker_tooltip",
        "Flame-like flicker of the brightness; 0 = steady, 1 = dips to full darkness",
        "Мерцание яркости, как у пламени; 0 — ровно, 1 — провалы в полную темноту");
    public static final IKey FLICKER_TEMPO = key("flicker_tempo", "Flicker tempo", "Темп мерцания");
    public static final IKey FLICKER_TEMPO_TOOLTIP = key("flicker_tempo_tooltip",
        "Flicker tempo: 1 = base (~3 Hz), higher = faster",
        "Темп мерцания: 1 — базовый (~3 Гц), больше — быстрее");
    public static final IKey USE_TEMPERATURE = key("use_temperature", "Use colour temperature", "Цветовая температура");
    public static final IKey USE_TEMPERATURE_TOOLTIP = key("use_temperature_tooltip",
        "Author the colour in kelvin instead of RGB — how a cinematographer thinks",
        "Задавать цвет в кельвинах вместо RGB — так мыслит оператор");
    public static final IKey TEMPERATURE = key("temperature", "Temperature", "Температура");
    public static final IKey TEMPERATURE_TOOLTIP = key("temperature_tooltip",
        "Kelvin: 1900 candle, 3200 tungsten, 5600 daylight, 8000+ shade",
        "Кельвины: 1900 свеча, 3200 лампа накаливания, 5600 дневной, 8000+ тень");
    public static final IKey BEAM_PROFILE = key("beam_profile", "Beam profile", "Профиль луча");
    public static final IKey BEAM_PROFILE_TOOLTIP = key("beam_profile_tooltip",
        "Photometric curve shaping how the beam falls off — a real fixture's signature",
        "Фотометрическая кривая спада луча — почерк настоящего светильника");
    public static final IKey IES_NONE = key("ies.none", "No profile", "Без профиля");
    public static final IKey IES_DOWNLIGHT = key("ies.downlight", "Downlight", "Даунлайт");
    public static final IKey IES_BATWING = key("ies.batwing", "Batwing", "Крылья");
    public static final IKey IES_RING = key("ies.ring", "Ring", "Кольцо");

    public static final IKey SECTION_SHADOWS = key("section.shadows", "Shadows", "Тени");
    public static final IKey CAST_SHADOWS = key("cast_shadows", "Cast shadows", "Отбрасывать тени");
    public static final IKey CAST_SHADOWS_TOOLTIP = key("cast_shadows_tooltip",
        "Off is cheaper and a legitimate look — that is what a fill light is",
        "Выключить дешевле, и это законный приём — так работает заполняющий свет");
    public static final IKey SOFTNESS = key("softness", "Softness", "Мягкость");
    public static final IKey SOFTNESS_TOOLTIP = key("softness_tooltip",
        "0 = crisp shadow; 1 = the softness the source's physical size dictates",
        "0 — резкая тень; 1 — мягкость по физическому размеру источника");

    public static final IKey SECTION_AFFECTS = key("section.affects", "Affects", "На что влияет");
    public static final IKey LIGHT_BLOCKS = key("light_blocks", "Light blocks", "Освещать блоки");
    public static final IKey LIGHT_BLOCKS_TOOLTIP = key("light_blocks_tooltip",
        "Light the world geometry", "Освещать геометрию мира");
    public static final IKey LIGHT_ENTITIES = key("light_entities", "Light entities", "Освещать сущности");
    public static final IKey LIGHT_ENTITIES_TOOLTIP = key("light_entities_tooltip",
        "Light actors, entities and items", "Освещать актёров, сущности и предметы");

    /* Point and spot */

    public static final IKey SECTION_BULB = key("section.bulb", "Bulb", "Лампа");
    public static final IKey BULB_SIZE = key("bulb_size", "Bulb size", "Размер лампы");
    public static final IKey BULB_SIZE_TOOLTIP = key("bulb_size_tooltip",
        "How big the lamp itself is: softer shadows and a wider highlight, no change to reach. Capped at half the range",
        "Размер самой лампы: мягче тени и шире блик, на дальность НЕ влияет. Ограничен половиной дальности");

    public static final IKey SECTION_CONE = key("section.cone", "Cone", "Конус");
    public static final IKey OUTER_ANGLE = key("outer_angle", "Outer angle", "Внешний угол");
    public static final IKey OUTER_ANGLE_TOOLTIP = key("outer_angle_tooltip",
        "Full width of the cone, in degrees — where the light ends",
        "Полная ширина конуса в градусах — где свет заканчивается");
    public static final IKey INNER_ANGLE = key("inner_angle", "Inner angle", "Внутренний угол");
    public static final IKey INNER_ANGLE_TOOLTIP = key("inner_angle_tooltip",
        "Where the falloff to the cone's edge begins; below the outer angle for a soft edge",
        "Где начинается спад к краю конуса; меньше внешнего — мягкий край");

    /* Area */

    public static final IKey SECTION_EMITTER = key("section.emitter", "Emitter", "Излучатель");
    public static final IKey SHAPE_TOOLTIP = key("shape_tooltip",
        "Softbox, ring light, neon tube or glowing ball — the shape shades and shadows accordingly",
        "Софтбокс, кольцевой свет, неоновая трубка или светящийся шар — форма задаёт свет и тени");
    public static final IKey SHAPE_RECT = key("shape.rect", "Rectangle", "Прямоугольник");
    public static final IKey SHAPE_DISC = key("shape.disc", "Disc", "Диск");
    public static final IKey SHAPE_TUBE = key("shape.tube", "Tube", "Трубка");
    public static final IKey SHAPE_SPHERE = key("shape.sphere", "Sphere", "Сфера");
    public static final IKey WIDTH = key("width", "Width", "Ширина");
    public static final IKey DIAMETER = key("diameter", "Diameter", "Диаметр");
    public static final IKey LENGTH = key("length", "Length", "Длина");
    public static final IKey WIDTH_TOOLTIP = key("width_tooltip",
        "Size of the emitter across, in blocks — this is what softens the shadows",
        "Размер излучателя поперёк, в блоках — именно он смягчает тени");
    public static final IKey HEIGHT = key("height", "Height", "Высота");
    public static final IKey HEIGHT_TOOLTIP = key("height_tooltip",
        "Height of the rectangular emitter, in blocks",
        "Высота прямоугольного излучателя, в блоках");
    public static final IKey TUBE_RADIUS = key("tube_radius", "Tube radius", "Радиус трубки");
    public static final IKey TUBE_RADIUS_TOOLTIP = key("tube_radius_tooltip",
        "Radius of the tube, in blocks", "Радиус трубки, в блоках");
    public static final IKey TWO_SIDED = key("two_sided", "Emit from both sides", "Светить с обеих сторон");
    public static final IKey TWO_SIDED_TOOLTIP = key("two_sided_tooltip",
        "The back face emits light too", "Обратная сторона тоже излучает");
    public static final IKey SPREAD = key("spread", "Spread", "Сужение");
    public static final IKey SPREAD_TOOLTIP = key("spread_tooltip",
        "Honeycomb grid: narrows the wash without dimming it. 1 = a bare panel",
        "Соты: сужают заливку, не приглушая её. 1 — голая панель");

    public static final IKey SECTION_BARN = key("section.barn", "Barn doors", "Шторки");
    public static final IKey BARN_TOP = key("barn.top", "Top", "Сверху");
    public static final IKey BARN_TOP_TOOLTIP = key("barn.top_tooltip",
        "Trims the light off past the top edge", "Срезает свет за верхним краем");
    public static final IKey BARN_BOTTOM = key("barn.bottom", "Bottom", "Снизу");
    public static final IKey BARN_BOTTOM_TOOLTIP = key("barn.bottom_tooltip",
        "Trims the light off past the bottom edge", "Срезает свет за нижним краем");
    public static final IKey BARN_LEFT = key("barn.left", "Left", "Слева");
    public static final IKey BARN_LEFT_TOOLTIP = key("barn.left_tooltip",
        "Trims the light off past the left edge", "Срезает свет за левым краем");
    public static final IKey BARN_RIGHT = key("barn.right", "Right", "Справа");
    public static final IKey BARN_RIGHT_TOOLTIP = key("barn.right_tooltip",
        "Trims the light off past the right edge", "Срезает свет за правым краем");
    public static final IKey BARN_SOFTNESS_TOOLTIP = key("barn.softness_tooltip",
        "How soft the trimmed edge is", "Насколько мягок срезанный край");

    /* Ambient */

    public static final IKey SECTION_AMBIENCE = key("section.ambience", "Ambience", "Заполнение");
    public static final IKey MODE_ZONE = key("mode.zone", "Zone", "Зона");
    public static final IKey MODE_HEMISPHERE = key("mode.hemisphere", "Hemisphere", "Полусфера");
    public static final IKey MODE_TOOLTIP = key("mode_tooltip",
        "Zone = a bounded pool of fill; Hemisphere = sky above, ground bounce below",
        "Зона — ограниченный объём заполнения; Полусфера — небо сверху, отсвет земли снизу");
    public static final IKey GROUND_BOUNCE = key("ground_bounce", "Ground bounce", "Отсвет земли");
    public static final IKey GROUND_BOUNCE_TOOLTIP = key("ground_bounce_tooltip",
        "Up-light bounced off this ground colour (hemisphere mode)",
        "Свет снизу, отражённый от земли этого цвета (режим полусферы)");
    public static final IKey OCCLUSION = key("occlusion", "Occlusion", "Затенение");
    public static final IKey OCCLUSION_TOOLTIP = key("occlusion_tooltip",
        "Contact darkening in creases; needs a patched shader pack",
        "Затемнение в складках и стыках; нужен пропатченный шейдерпак");

    public static final IKey SECTION_VOLUME = key("section.volume", "Volume", "Объём");
    public static final IKey VOLUME_SPHERE = key("volume.sphere", "Sphere", "Сфера");
    public static final IKey VOLUME_BOX = key("volume.box", "Box", "Бокс");
    public static final IKey VOLUME_TOOLTIP = key("volume_tooltip",
        "Shape of the zone. The sphere takes its radius from Range; the box rotates with the form",
        "Форма зоны. Сфера берёт радиус из Дальности; бокс вращается вместе с формой");
    public static final IKey BOX_SIZE = key("box_size", "Box half-size X / Y / Z", "Полуразмер бокса X / Y / Z");
    public static final IKey EDGE_FALLOFF = key("edge_falloff", "Edge falloff", "Мягкость края");
    public static final IKey EDGE_FALLOFF_TOOLTIP = key("edge_falloff_tooltip",
        "Softens the volume's boundary so the fill does not end on a line",
        "Смягчает границу объёма, чтобы заполнение не обрывалось по линии");

    /* Air tab */

    public static final IKey SECTION_AIR = key("section.air", "Air", "Воздух");
    public static final IKey BEAM = key("beam", "Beam", "Луч");
    public static final IKey BEAM_TOOLTIP = key("beam_tooltip",
        "Strength of the beam visible in the air; no upper limit",
        "Сила видимого в воздухе луча; верхнего предела нет");
    public static final IKey HAZE = key("haze", "Haze", "Дымка");
    public static final IKey HAZE_TOOLTIP = key("haze_tooltip",
        "Fog glowing around the lamp in every direction — a streetlamp in mist",
        "Туман, светящийся вокруг лампы во все стороны — фонарь в тумане");
    public static final IKey DUST = key("dust", "Dust", "Пыль");
    public static final IKey DUST_TOOLTIP = key("dust_tooltip",
        "Motes drifting and twinkling inside the beam — the projector-room look",
        "Пылинки, плывущие и мерцающие в луче — вид кинобудки");
    public static final IKey MOTE_SIZE = key("mote_size", "Mote size", "Размер пылинок");
    public static final IKey MOTE_SIZE_TOOLTIP = key("mote_size_tooltip",
        "Size of the dust motes", "Размер пылинок");
    public static final IKey PRISM = key("prism", "Prism", "Призма");
    public static final IKey PRISM_TOOLTIP = key("prism_tooltip",
        "Rainbow caustics on surfaces and a spectral rim on the beam — light through faceted glass",
        "Радужные каустики на поверхностях и спектральная кромка луча — свет сквозь гранёное стекло");
    public static final IKey PRISM_SCALE = key("prism_scale", "Prism scale", "Масштаб призмы");
    public static final IKey PRISM_SCALE_TOOLTIP = key("prism_scale_tooltip",
        "Size of the caustic pattern's cells", "Размер ячеек каустического узора");
    public static final IKey BOUNCE = key("bounce", "Bounce", "Отскок");
    public static final IKey BOUNCE_TOOLTIP = key("bounce_tooltip",
        "One indirect bounce: fill light coloured by the first surface the beam hits",
        "Один отскок: заполняющий свет в цвет первой поверхности, куда попал луч");

    /* Style tab */

    public static final IKey SECTION_SURFACES = key("section.surfaces", "Surfaces", "Поверхности");
    public static final IKey RIM = key("rim", "Rim", "Контровой");
    public static final IKey RIM_TOOLTIP = key("rim_tooltip",
        "Edge light along silhouettes, for the key/fill/rim setup",
        "Свет по силуэту — третий источник классической схемы");
    public static final IKey RIM_WIDTH = key("rim_width", "Rim width", "Ширина контрового");
    public static final IKey RIM_WIDTH_TOOLTIP = key("rim_width_tooltip",
        "Width of the rim strip across the silhouette",
        "Ширина полосы контрового света по силуэту");
    public static final IKey GRAZING_SHEEN = key("grazing_sheen", "Grazing sheen", "Скользящий блеск");
    public static final IKey GRAZING_SHEEN_TOOLTIP = key("grazing_sheen_tooltip",
        "Grazing-angle Fresnel inside the highlight: 1 = physics, 0 = off, 2 = hot",
        "Френель на скользящем угле внутри блика: 1 — физика, 0 — выключено, 2 — жарко");
    public static final IKey TRANSLUCENCY = key("translucency", "Translucency", "Просвет");
    public static final IKey TRANSLUCENCY_TOOLTIP = key("translucency_tooltip",
        "Light bleeding through thin things — backlit leaves, fabric, banners",
        "Свет сквозь тонкое — листва на просвет, ткань, флаги");
    public static final IKey OUTLINE = key("outline", "Outline", "Контур");
    public static final IKey OUTLINE_TOOLTIP = key("outline_tooltip",
        "Hardens the rim into an inked contour", "Превращает контровой в рисованный контур");
    public static final IKey OUTLINE_WIDTH = key("outline_width", "Outline width", "Толщина контура");
    public static final IKey OUTLINE_WIDTH_TOOLTIP = key("outline_width_tooltip",
        "Contour thickness in pixels", "Толщина контура в пикселях");
    public static final IKey OUTLINE_BLUR = key("outline_blur", "Outline blur", "Размытие контура");
    public static final IKey OUTLINE_BLUR_TOOLTIP = key("outline_blur_tooltip",
        "Softens the contour", "Смягчает контур");
    public static final IKey OUTLINE_INNER = key("outline_inner", "Inner outline", "Внутренний контур");
    public static final IKey OUTLINE_INNER_TOOLTIP = key("outline_inner_tooltip",
        "Edge-detect lines inside the geometry — creases and overlaps, not just the silhouette",
        "Линии внутри геометрии — складки и пересечения, не только силуэт");
    public static final IKey OUTLINE_TARGET = key("outline_target", "Outline target", "Цель контура");
    public static final IKey OUTLINE_TARGET_TOOLTIP = key("outline_target_tooltip",
        "What the contour draws on: the world, the models, or both",
        "По чему рисовать контур: по миру, по моделям или по обоим");
    public static final IKey OUTLINE_TARGET_TITLE = key("outline_target_title", "Outline target", "Цель контура");
    public static final IKey OUTLINE_TARGET_BOTH = key("outline_target_both", "World & models", "Мир и модели");
    public static final IKey OUTLINE_TARGET_MODELS = key("outline_target_models", "Models only", "Только модели");
    public static final IKey OUTLINE_TARGET_WORLD = key("outline_target_world", "World only", "Только мир");
    public static final IKey OUTLINE_BLEND = key("outline_blend", "Outline blend", "Смешивание контура");
    public static final IKey OUTLINE_BLEND_TOOLTIP = key("outline_blend_tooltip",
        "How the contour mixes into the picture: add, screen or overlay",
        "Как контур смешивается с картинкой: add, screen или overlay");
    public static final IKey OUTLINE_BLEND_TITLE = key("outline_blend_title", "Outline blend mode", "Режим смешивания контура");
    public static final IKey OUTLINE_BLEND_ADD = key("outline_blend_add", "Add", "Add");
    public static final IKey OUTLINE_BLEND_SCREEN = key("outline_blend_screen", "Screen", "Screen");
    public static final IKey OUTLINE_BLEND_OVERLAY = key("outline_blend_overlay", "Overlay", "Overlay");

    public static final IKey SECTION_TOON = key("section.toon", "Toon", "Тун");
    public static final IKey TOON_SHADING = key("toon_shading", "Toon shading", "Тун-шейдинг");
    public static final IKey TOON_SHADING_TOOLTIP = key("toon_shading_tooltip",
        "Two-tone anime shading for this lamp: flat lit side, flat shadow side",
        "Двухтоновая аниме-заливка от этой лампы: плоский свет, плоская тень");
    public static final IKey EDGE_SOFTNESS = key("edge_softness", "Edge softness", "Мягкость границы");
    public static final IKey EDGE_SOFTNESS_TOOLTIP = key("edge_softness_tooltip",
        "Width of the lit-to-shadow transition", "Ширина перехода от света к тени");
    public static final IKey SHADOW_TINT = key("shadow_tint", "Shadow tint", "Холод тени");
    public static final IKey SHADOW_TINT_TOOLTIP = key("shadow_tint_tooltip",
        "How far the shadow side is pushed toward cool tones",
        "Насколько теневая сторона уходит в холодные тона");
    public static final IKey SHADOW_BRIGHTNESS = key("shadow_brightness", "Shadow brightness", "Яркость тени");
    public static final IKey SHADOW_BRIGHTNESS_TOOLTIP = key("shadow_brightness_tooltip",
        "Brightness kept on the shadow side", "Сколько света остаётся на теневой стороне");

    public static final IKey SECTION_FLARE = key("section.flare", "Flare", "Блик");
    public static final IKey LENS_FLARE = key("lens_flare", "Lens flare", "Блик объектива");
    public static final IKey LENS_FLARE_TOOLTIP = key("lens_flare_tooltip",
        "The camera's answer to this lamp: glow, streaks and the ghost train",
        "Ответ камеры на эту лампу: свечение, лучи и цепочка призраков");
    public static final IKey FLARE_STYLE = key("flare_style", "Style", "Стиль");
    public static final IKey FLARE_STYLE_TITLE = key("flare_style_title", "Flare style", "Стиль блика");
    public static final IKey FLARE_STYLE_TOOLTIP = key("flare_style_tooltip",
        "Which lens the flare pretends to come from",
        "Какой объектив изображает блик");
    public static final IKey FLARE_STAR = key("flare.star", "Star", "Звезда");
    public static final IKey FLARE_ANAMORPHIC = key("flare.anamorphic", "Anamorphic", "Анаморф");
    public static final IKey FLARE_CLEAN = key("flare.clean", "Clean", "Чистый");
    public static final IKey FLARE_JJ = key("flare.jj", "JJ", "JJ");
    public static final IKey FLARE_SUN = key("flare.sun", "Sun", "Солнце");
    public static final IKey FLARE_SEARCHLIGHT = key("flare.searchlight", "Searchlight", "Прожектор");
    public static final IKey FLARE_TACTICAL = key("flare.tactical", "Tactical", "Тактический");
    public static final IKey FLARE_VINTAGE = key("flare.vintage", "Vintage", "Винтаж");
    public static final IKey FLARE_BOKEH = key("flare.bokeh", "Bokeh", "Боке");

    /* Presets */

    public static final IKey PRESET_SOFT_KEY = key("preset.soft_key", "Soft key", "Мягкий рисующий");
    public static final IKey PRESET_HARD_KEY = key("preset.hard_key", "Hard key", "Жёсткий рисующий");
    public static final IKey PRESET_RIM = key("preset.rim", "Rim / backlight", "Контровой / подсветка сзади");
    public static final IKey PRESET_NOIR = key("preset.noir", "Noir", "Нуар");
    public static final IKey PRESET_ANIME = key("preset.anime", "Anime portrait", "Аниме-портрет");
    public static final IKey PRESET_FOGGY = key("preset.foggy", "Foggy practical", "Фонарь в тумане");
    public static final IKey PRESET_BLENDER_RIM = key("preset.blender_rim", "Blender rim", "Контровой Blender");

    /* Light groups, shown in the replay's properties panel */

    public static final IKey GROUPS = key("groups", "Light groups", "Группы света");
    public static final IKey GROUPS_TOOLTIP = key("groups_tooltip",
        "Light affects only actors in the selected replay categories; empty = everything",
        "Свет действует только на актёров из выбранных категорий реплеев; пусто — на всех");
    public static final IKey GROUPS_FILTER = key("groups_filter", "Filter by groups", "Фильтр по группам");
    public static final IKey GROUPS_FILTER_TOOLTIP = key("groups_filter_tooltip",
        "When off, the selection is kept but the light affects everything",
        "Когда выключен, выбор сохраняется, но свет действует на всех");

    /* BBS settings — the addon's own category, registered by BBSSettingsMixin. These are not IKeys:
     * the settings UI looks its labels up by string through L10n at the moment it builds a row, so
     * only the CONTENT has to be in the map (same path BBS's own options take). The key mirrors the
     * settings path — bbs.config.<category>.<option> — as UIValueFactory derives it. */
    static
    {
        config("title", "VFX LIGHTS", "VFX LIGHTS");

        config("actor_preset", "Second layer quality", "Качество второго слоя");
        config("actor_preset-comment",
            "The actor-fitted millimetre shadow map (second skin layer), on its own dial:"
                + " Off disables it, Medium uses the standard fit cone, High tightens the cone"
                + " (~1.3x texel density on the body, limbs stay in), Ultra tightens it to the"
                + " coverage ceiling (~1.3x over High, close-up mode — wide choreography belongs"
                + " to High)."
                + " The map now reaches as far"
                + " as the lamp's own range, like the main shadows. Takes effect immediately.",
            "Детальная миллиметровая карта теней актёров (второй слой скина), отдельным"
                + " регулятором: «Выкл» отключает её, «Среднее» — стандартный конус,"
                + " «Высокое» уплотняет конус (~в 1.3 раза больше плотности на теле, конечности"
                + " остаются внутри), «Ультра» — до предела покрытия (ещё ~1.3x к «Высокому», режим"
                + " крупных планов; широкая хореография — на «Высокое»). Карта теперь работает"
                + " до предела дальности лампы, как"
                + " основные тени. Применяется сразу.");

        config("quality_preset", "Quality preset", "Пресет качества");
        config("quality_preset-comment",
            "One dial for every quality cost: shadow map resolution (512/1024/2048 texels"
                + " per lamp), shadow atlas memory, edge filtering and volumetric beam density."
                + " Medium is the look the mod shipped with. Low trades sharpness for frame rate"
                + " on heavy rigs; High and Ultra add filtering and beam detail. The atlas grows"
                + " only within what the card has free past a 2.5 GB reserve, and the actor"
                + " detail map (second skin layer) has its own dial below. Takes effect"
                + " immediately.",
            "Один регулятор для всех статей качества: разрешение теневых карт (512/1024/2048"
                + " текселей на лампу), память теневого атласа, фильтрация краёв и плотность"
                + " объёмных лучей. «Среднее» — вид, с которым мод вышел. «Низкое» меняет чёткость"
                + " на кадры на тяжёлых сценах; «Высокое» и «Ультра» добавляют фильтрации и"
                + " детали лучей. Атлас растёт только в пределах свободной видеопамяти за вычетом"
                + " резерва 2.5 ГБ, а у детальной карты актёров (второй слой) — свой регулятор"
                + " ниже. Применяется сразу.");
    }

    private static void config(String suffix, String en, String ru)
    {
        STRINGS.put("bbs.config.vfxlights." + suffix, new String[] {en, ru});
    }

    private static IKey key(String suffix, String en, String ru)
    {
        String id = PREFIX + suffix;

        STRINGS.put(id, new String[] {en, ru});

        mchorse.bbs_mod.l10n.L10n l10n = mchorse.bbs_mod.BBSModClient.getL10n();

        if (l10n == null)
        {
            /* Client-init order against BBS's L10n is not guaranteed (a cold config crashed the
             * whole client at clinit). Hand out an own instance now; apply() rewrites its content
             * from STRINGS on every later L10n reload, so localization still lands. */
            LangKey fallback = new LangKey(null, id, en);

            FALLBACKS.put(id, fallback);

            return fallback;
        }

        return L10n.lang(id);
    }

    /**
     * Push the current language's text into the loaded string map. Called once at client init and
     * again on every language switch.
     */
    public static void apply(L10n l10n)
    {
        if (l10n == null)
        {
            return;
        }

        boolean ru = "ru_ru".equals(BBSSettings.language.get());

        for (Map.Entry<String, String[]> entry : STRINGS.entrySet())
        {
            LangKey key = l10n.getKey(entry.getKey());

            /* A key that came from an actual language file was authored by someone; leave it alone. */
            if (key.getOrigin() == null)
            {
                key.content = entry.getValue()[ru ? 1 : 0];
            }

            /* Instances handed out before the L10n existed need the same text directly — the UI
             * holds them, not the registered key. */
            LangKey fallback = FALLBACKS.get(entry.getKey());

            if (fallback != null)
            {
                fallback.content = key.getOrigin() == null ? entry.getValue()[ru ? 1 : 0] : key.content;
            }
        }
    }

    /** Public — the BBS event bus invokes {@link Subscribe @Subscribe} methods by reflection. */
    @Subscribe
    public void onL10nReload(L10nReloadEvent event)
    {
        apply(event.l10n);
    }
}
