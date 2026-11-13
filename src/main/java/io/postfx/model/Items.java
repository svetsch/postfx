package io.postfx.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Tree helpers. */
public final class Items {
    private Items() {}

    /** All requests below the given node, depth first, in collection order. */
    public static List<RequestItem> flatten(Item root) {
        List<RequestItem> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Item item, List<RequestItem> out) {
        if (item instanceof RequestItem r) {
            out.add(r);
        } else if (item instanceof Folder f) {
            for (Item child : f.items) collect(child, out);
        }
    }

    /** Folders from the root (inclusive) down to the direct parent of the target (inclusive); empty if not found. */
    public static Optional<List<Folder>> ancestors(Folder root, Item target) {
        List<Folder> path = new ArrayList<>();
        return find(root, target, path) ? Optional.of(path) : Optional.empty();
    }

    private static boolean find(Folder current, Item target, List<Folder> path) {
        path.add(current);
        for (Item child : current.items) {
            if (child == target) return true;
            if (child instanceof Folder f && find(f, target, path)) return true;
        }
        path.remove(path.size() - 1);
        return false;
    }

    public static Folder parentOf(Folder root, Item target) {
        return ancestors(root, target).map(l -> l.get(l.size() - 1)).orElse(null);
    }
}
