package games.qore.foreman.api

/**
 * The Foreman server's own MCP tools: the server names its built-in tools with these (other plugins can't publish a
 * tool under one of these names), and the client knows them even on a server without Foreman, to tell the AI what it
 * is missing there. Tools other plugins publish freely aren't here: they are only known while their plugin runs.
 *
 * [requires]: a tool published only on a condition (another plugin on the server), told to the AI when it's missing.
 */
enum class ForemanTool(val id: String, val group: String, val requires: String? = null) {
    // Inspect: read the world.
    GET_CONTEXT("get_context", "Inspect", WORLD_EDIT),
    READ_STYLE_GUIDE("read_style_guide", "Inspect", WORLD_EDIT),
    SCAN_AREA("scan_area", "Inspect", WORLD_EDIT),
    READ_BLOCKS("read_blocks", "Inspect", WORLD_EDIT),
    FIND_BLOCKS("find_blocks", "Inspect", WORLD_EDIT),
    RENDER("render", "Inspect", WORLD_EDIT),
    WORLD_PROFILE("world_profile", "Inspect", WORLD_EDIT),
    SET_MARK("set_mark", "Inspect", WORLD_EDIT),
    DIFF_REGIONS("diff_regions", "Inspect", WORLD_EDIT),

    // Quality: check builds, measure sites, reserve space, lock areas to a builder.
    CHECK("check", "Quality", WORLD_EDIT),
    LOOK("look", "Quality", WORLD_EDIT),
    MEASURE_SITE("measure_site", "Quality", WORLD_EDIT),
    CLAIM("claim", "Quality", WORLD_EDIT),
    CHECK_LAYOUT("check_layout", "Quality", WORLD_EDIT),
    LOCK_AREA("lock_area", "Quality", WORLD_EDIT),
    UNLOCK_AREA("unlock_area", "Quality", WORLD_EDIT),

    // Edit: change blocks, move regions, undo.
    EDIT("edit", "Edit", WORLD_EDIT),
    TRANSFORM_REGION("transform_region", "Edit", WORLD_EDIT),
    UNDO("undo", "Edit", WORLD_EDIT),
    HISTORY("history", "Edit", WORLD_EDIT),
    WORLDEDIT("worldedit", "Edit", WORLD_EDIT),

    // Terrain: ground, grading, roads.
    FOUNDATION("foundation", "Terrain", WORLD_EDIT),
    BLEND_TERRAIN("blend_terrain", "Terrain", WORLD_EDIT),
    SMOOTH_TERRAIN("smooth_terrain", "Terrain", WORLD_EDIT),
    MAKE_GROUND("make_ground", "Terrain", WORLD_EDIT),
    GRADE("grade", "Terrain", WORLD_EDIT),
    BUILD_PATH("build_path", "Terrain", WORLD_EDIT),

    // Land: fields.
    MAKE_FIELDS("make_fields", "Land", WORLD_EDIT),

    // Build: whole buildings.
    BUILD_STRUCTURE("build_structure", "Build", WORLD_EDIT),
    DEMOLISH("demolish", "Build", WORLD_EDIT),
    LIST_STYLES("list_styles", "Build", WORLD_EDIT),
    POINTS("points", "Build", WORLD_EDIT),
    LAYOUT_TYPES("layout_types", "Build", WORLD_EDIT),

    // Repair: unreachable rooms, broken terrain.
    CONNECT_DOORS("connect_doors", "Repair", WORLD_EDIT),
    REPAIR_TERRAIN("repair_terrain", "Repair", WORLD_EDIT),

    // Assets: schematics and asset areas, the instances pastes record.
    LIST_ASSETS("list_assets", "Assets", WORLD_EDIT),
    INDEX_ASSETS("index_assets", "Assets", WORLD_EDIT),
    PREVIEW_ASSET("preview_asset", "Assets", WORLD_EDIT),
    ASSET_CONTENT("asset_content", "Assets", WORLD_EDIT),
    SCATTER_ASSETS("scatter_assets", "Assets", WORLD_EDIT),
    PASTE_ASSET("paste_asset", "Assets", WORLD_EDIT),
    COPY_REGION("copy_region", "Assets", WORLD_EDIT),
    SAVE_ASSET("save_asset", "Assets", WORLD_EDIT),
    DEFINE_ASSET("define_asset", "Assets", WORLD_EDIT),
    FIT_ASSET("fit_asset", "Assets", WORLD_EDIT),
    RESIZE_ASSET("resize_asset", "Assets", WORLD_EDIT),
    MOVE_ASSET("move_asset", "Assets", WORLD_EDIT),
    LIST_INSTANCES("list_instances", "Assets", WORLD_EDIT),
    INSTANCE_INFO("instance_info", "Assets", WORLD_EDIT),
    DETACH_INSTANCE("detach_instance", "Assets", WORLD_EDIT),
    ATTACH_INSTANCE("attach_instance", "Assets", WORLD_EDIT),
    FORGET_INSTANCE("forget_instance", "Assets", WORLD_EDIT),
    DELETE_INSTANCE("delete_instance", "Assets", WORLD_EDIT),
    ROTATE_INSTANCE("rotate_instance", "Assets", WORLD_EDIT),
    SYNC_INSTANCE("sync_instance", "Assets", WORLD_EDIT),
    SYNC_ASSET("sync_asset", "Assets", WORLD_EDIT),
    OPEN_ASSET_SOURCE("open_asset_source", "Assets", WORLD_EDIT),

    // Models: the resources' item models.
    PREVIEW_MODEL("preview_model", "Models", WORLD_EDIT),
    LIST_MODELS("list_models", "Models", WORLD_EDIT),

    // Displays: item, block and text display entities one by one (furniture and decor).
    LIST_DISPLAYS("list_displays", "Displays", WORLD_EDIT),
    EDIT_DISPLAYS("edit_displays", "Displays", WORLD_EDIT),
    SPAWN_DISPLAY("spawn_display", "Displays", WORLD_EDIT),
    REMOVE_DISPLAYS("remove_displays", "Displays", WORLD_EDIT),

    // Objects: things plugins draw with entities (cables, props), declared through ForemanObjects.
    OBJECT_KINDS("object_kinds", "Objects", WORLD_EDIT),
    LIST_OBJECTS("list_objects", "Objects", WORLD_EDIT),
    PLACE_OBJECTS("place_objects", "Objects", WORLD_EDIT),
    EDIT_OBJECTS("edit_objects", "Objects", WORLD_EDIT),
    REMOVE_OBJECTS("remove_objects", "Objects", WORLD_EDIT),

    // Panels: what plugins show and let builders change about their things, and the relations between them (ForemanPanels).
    PANEL_GET("panel_get", "Panels"),
    PANEL_EDIT("panel_edit", "Panels"),
    PANEL_ACTION("panel_action", "Panels"),
    PANEL_FACTS("panel_facts", "Panels"),
    LIST_RELATIONS("list_relations", "Panels"),
    LINK_TARGETS("link_targets", "Panels"),
    UNLINK("unlink", "Panels"),
    RELINK("relink", "Panels"),

    // Skills: how to do a kind of task here, read when needed.
    READ_SKILL("read_skill", "Skills"),
    SAVE_SKILL("save_skill", "Skills"),

    // Lessons learned on the server.
    LEARN("learn", "Lessons"),
    LESSONS("lessons", "Lessons"),

    // Furniture: world tools, published while a furniture plugin registers a FurnitureProvider.
    LIST_FURNITURE("list_furniture", "Furniture", FURNITURE_PROVIDER),
    PLACE_FURNITURE("place_furniture", "Furniture", FURNITURE_PROVIDER),
    REMOVE_FURNITURE("remove_furniture", "Furniture", FURNITURE_PROVIDER),

    // Other plugins: code tools where their commands fall short (skills cover the rest), published while they run.
    MODELENGINE_MODELS("modelengine_models", "ModelEngine", "ModelEngine on the server"),

    // Testing: finding item duplication in plugins, players kept and traced across tests (the player needs foreman.test).
    ITEM_LEDGER("item_ledger", "Testing"),
    INVENTORY_WATCH("inventory_watch", "Testing"),
    TEST_RESTORE("test_restore", "Testing"),
    TEST_PLAYER("test_player", "Testing"),
    SERVER_TRACE("server_trace", "Testing"),
    TEST_PLAYERS("test_players", "Testing"),

    // Server: developing plugins on the server (commands, log, plugin jars, performance).
    SERVER_COMMAND("server_command", "Server"),
    SERVER_LOG("server_log", "Server"),
    SERVER_LOG_MARK("server_log_mark", "Server"),
    SERVER_PLUGINS("server_plugins", "Server"),
    SERVER_STATUS("server_status", "Server"),
    SERVER_PROFILE("server_profile", "Server"),
    SERVER_LISTENERS("server_listeners", "Server"),
    SERVER_PACKETS("server_packets", "Server"),
    SERVER_RELOAD_PLUGIN("server_reload_plugin", "Server", "PlugMan or PlugManX on the server"),
    ;

    /** A world tool: it needs FastAsyncWorldEdit on the server. */
    val world: Boolean get() = requires == WORLD_EDIT || requires == FURNITURE_PROVIDER

    companion object {
        private val byId = entries.associateBy { it.id }

        fun of(id: String): ForemanTool? = byId[id]
    }
}

private const val WORLD_EDIT = "FastAsyncWorldEdit on the server"
private const val FURNITURE_PROVIDER = "FastAsyncWorldEdit and a furniture plugin registering a FurnitureProvider on the server"
