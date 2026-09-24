package mchorse.bbs_mod.ui.utils.pose;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Simple manager for bone categories (ported from BBS CML Edition).
 *
 * Keeps, for every pose group (a String key), a set of named categories and the bones
 * inside each of them. It is saved to bone_categories.json in the BBS settings folder:
 *
 * {
 *   "poseGroup": {
 *     "Category A": ["bone1", "bone2"],
 *     "Category B": ["bone3"]
 *   }
 * }
 */
public class BoneCategoriesManager
{
    private static final String FILE_NAME = "bone_categories.json";

    private final Map<String, Map<String, List<String>>> cache = new HashMap<>();

    public BoneCategoriesManager()
    {
        this.load();
    }

    private File getFile()
    {
        return BBSMod.getSettingsPath(FILE_NAME);
    }

    private void load()
    {
        try
        {
            BaseType type = DataToString.read(this.getFile());

            if (type != null && type.isMap())
            {
                MapType map = (MapType) type;

                for (String group : map.keys())
                {
                    Map<String, List<String>> categories = new LinkedHashMap<>();
                    MapType cats = map.getMap(group);

                    if (cats != null)
                    {
                        for (String catName : cats.keys())
                        {
                            List<String> bones = new ArrayList<>();
                            ListType list = cats.getList(catName);

                            if (list != null)
                            {
                                for (int i = 0; i < list.size(); i++)
                                {
                                    bones.add(list.getString(i));
                                }
                            }

                            categories.put(catName, bones);
                        }
                    }

                    this.cache.put(group, categories);
                }
            }
        }
        catch (IOException e)
        {
            /* The file doesn't exist yet or can't be read: start with nothing */
        }
    }

    private void save()
    {
        MapType root = new MapType();

        for (Map.Entry<String, Map<String, List<String>>> entry : this.cache.entrySet())
        {
            MapType cats = new MapType();

            for (Map.Entry<String, List<String>> cat : entry.getValue().entrySet())
            {
                ListType bones = new ListType();

                for (String bone : cat.getValue())
                {
                    bones.addString(bone);
                }

                cats.put(cat.getKey(), bones);
            }

            root.put(entry.getKey(), cats);
        }

        DataToString.writeSilently(this.getFile(), root, true);
    }

    private Map<String, List<String>> group(String groupKey)
    {
        return this.cache.computeIfAbsent(groupKey == null ? "" : groupKey, (g) -> new LinkedHashMap<>());
    }

    /* API */

    public List<String> getCategories(String groupKey)
    {
        return new ArrayList<>(this.group(groupKey).keySet());
    }

    public List<String> getBones(String groupKey, String category)
    {
        return new ArrayList<>(this.group(groupKey).getOrDefault(category, Collections.emptyList()));
    }

    public void addCategory(String groupKey, String category)
    {
        this.group(groupKey).putIfAbsent(category, new ArrayList<>());
        this.save();
    }

    public void removeCategory(String groupKey, String category)
    {
        this.group(groupKey).remove(category);
        this.save();
    }

    public void renameCategory(String groupKey, String oldName, String newName)
    {
        if (Objects.equals(oldName, newName))
        {
            return;
        }

        Map<String, List<String>> categories = this.group(groupKey);
        List<String> bones = categories.remove(oldName);

        categories.put(newName, bones == null ? new ArrayList<>() : bones);
        this.save();
    }

    public void addBone(String groupKey, String category, String bone)
    {
        List<String> bones = this.group(groupKey).computeIfAbsent(category, (c) -> new ArrayList<>());

        if (!bones.contains(bone))
        {
            bones.add(bone);
            this.save();
        }
    }

    public void removeBone(String groupKey, String category, String bone)
    {
        List<String> bones = this.group(groupKey).get(category);

        if (bones != null && bones.remove(bone))
        {
            this.save();
        }
    }
}
