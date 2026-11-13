package io.postfx.model;

import java.util.ArrayList;
import java.util.List;

/** A folder of requests. Folders can define auth and scripts inherited by their children. */
public class Folder extends Item {
    public List<Item> items = new ArrayList<>();
    public Auth auth = new Auth(AuthType.INHERIT);
    public String preRequestScript = "";
    public String testScript = "";

    public Folder() {}

    public Folder(String name) {
        this.name = name;
    }
}
