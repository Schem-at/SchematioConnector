package io.schemat.axiom;

import com.moulberry.axiomclientapi.CustomTool;
import com.moulberry.axiomclientapi.service.ToolRegistryService;
import imgui.moulberry92.ImGui;
import imgui.moulberry92.flag.ImGuiInputTextFlags;
import imgui.moulberry92.flag.ImGuiKey;
import imgui.moulberry92.type.ImString;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import java.net.URI;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Drawn exclusively by Axiom through its CustomTool extension point. */
public final class AxiomLibraryTool implements CustomTool {
    private final SchematioClient api = new SchematioClient(URI.create(System.getProperty("schematio.axiom.endpoint", "https://schemat.io/api/v1")));
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "schematio-axiom-transfer"); thread.setDaemon(true); return thread;
    });
    private final ImString query = new ImString(160);
    private final TransferGuard guard = new TransferGuard();
    private SchematioClient.Page page = new SchematioClient.Page(List.of(), 1, 1, 0);
    private SchematioClient.Build selected;
    private Future<?> pending;
    private volatile String status = "Search Schematio, then load a build into Axiom's clipboard.";
    private boolean busy;
    private boolean searched;
    private int searchGeneration;
    private String searchedQuery = "";

    public static void register() {
        var tool = new AxiomLibraryTool();
        ServiceLoader.load(ToolRegistryService.class).findFirst().orElseThrow().register(tool);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> tool.worker.shutdownNow());
    }

    @Override public String name() { return "Schematio"; }

    @Override public void displayImguiOptions() {
        // Axiom owns this ImGui context and frame. No new windows, styles or global callbacks.
        ImGui.text("Schematio library");
        ImGui.textDisabled("Builds from the community");
        ImGui.spacing();
        ImGui.beginDisabled(busy);
        ImGui.setNextItemWidth(-1);
        // EnterReturnsTrue leaves ImString's cached Java value stale until Enter in this binding.
        // Update the value on each edit so clicking Search uses the same text the player sees.
        ImGui.inputTextWithHint("##schematio-query", "Find a build...", query, ImGuiInputTextFlags.None);
        boolean enter = ImGui.isItemFocused() && ImGui.isKeyPressed(ImGuiKey.Enter);
        if (ImGui.button("Search", -1, 0) || enter) search(query.get(), 1);
        ImGui.endDisabled();
        if (searched) {
            ImGui.spacing();
            ImGui.textDisabled(page.total() + " builds  /  page " + page.number() + " of " + page.last());
            ImGui.beginChild("##schematio-builds", 0, Math.min(240, ImGui.getTextLineHeightWithSpacing() * 16));
            for (var build : page.builds()) {
                ImGui.pushID(build.id());
                if (ImGui.selectable(build.name(), build.equals(selected))) selected = build;
                if (ImGui.isItemHovered()) ImGui.setTooltip(build.name() + "\nby " + build.author());
                ImGui.textDisabled("by " + build.author());
                ImGui.spacing();
                ImGui.popID();
            }
            ImGui.endChild();
            ImGui.beginDisabled(busy || page.number() <= 1);
            if (ImGui.button("Previous")) search(searchedQuery, page.number() - 1);
            ImGui.endDisabled();
            ImGui.sameLine();
            ImGui.beginDisabled(busy || page.number() >= page.last());
            if (ImGui.button("Next")) search(searchedQuery, page.number() + 1);
            ImGui.endDisabled();
        }
        if (selected != null) {
            ImGui.separator();
            ImGui.textWrapped(selected.name());
            ImGui.beginDisabled(busy || !AxiomClipboardAdapter.permitted() || AxiomClipboardAdapter.placing());
            if (ImGui.button("Load into Axiom", -1, 0)) load(selected);
            ImGui.endDisabled();
            if (ImGui.button("Copy page link", -1, 0)) {
                Minecraft.getInstance().keyboardHandler.setClipboard(selected.webUrl());
                status = "Schematio page link copied.";
            }
            if (!AxiomClipboardAdapter.permitted()) ImGui.textWrapped("This server has disabled Axiom imports.");
            else if (AxiomClipboardAdapter.placing()) ImGui.textWrapped("Finish or cancel your current placement first.");
        }
        ImGui.separator();
        ImGui.textWrapped(status);
        if (busy && ImGui.button("Cancel", -1, 0)) cancel();
        ImGui.spacing();
        ImGui.textDisabled("Axiom controls placement and undo.");
    }

    // All controller methods and completions run on the client thread. Only IO/decoding use worker.
    void search(String text, int number) {
        if (busy) return;
        int generation = ++searchGeneration;
        busy = true;
        status = "Searching Schematio...";
        pending = worker.submit(() -> {
            try {
                var result = api.search(text, number);
                Minecraft.getInstance().execute(() -> {
                    if (generation != searchGeneration) return;
                    page = result; selected = null; searched = true; searchedQuery = text; busy = false;
                    status = result.builds().isEmpty() ? "No builds found. Try another search." : "Choose a build to load.";
                });
            } catch (Exception e) {
                Minecraft.getInstance().execute(() -> {
                    if (generation != searchGeneration) return;
                    busy = false; status = message(e);
                });
            }
        });
    }

    void load(SchematioClient.Build build) {
        var mc = Minecraft.getInstance();
        if (busy || mc.level == null) return;
        if (!AxiomClipboardAdapter.permitted() || AxiomClipboardAdapter.placing()) return;
        var ticket = guard.begin(mc.level, AxiomClipboardAdapter.current());
        busy = true;
        status = "Downloading " + build.name() + "...";
        long started = System.nanoTime();
        pending = worker.submit(() -> {
            try {
                byte[] bytes = api.download(build.id());
                long downloaded = System.nanoTime();
                if (Thread.currentThread().isInterrupted()) return;
                mc.execute(() -> { if (guard.isCurrent(ticket)) status = "Preparing Axiom clipboard..."; });
                var prepared = AxiomClipboardAdapter.prepare(bytes, build.name());
                long decoded = System.nanoTime();
                mc.execute(() -> {
                    if (!guard.isCurrent(ticket)) return;
                    String rejection = guard.rejection(ticket, mc.level, AxiomClipboardAdapter.current(),
                            AxiomClipboardAdapter.placing(), AxiomClipboardAdapter.permitted());
                    busy = false;
                    if (rejection != null) { status = rejection; return; }
                    AxiomClipboardAdapter.commit(prepared);
                    status = "Ready in Axiom's clipboard. Use Axiom's Paste shortcut to position, then Enter to place.";
                    SchematioAxiomClient.LOG.info("Loaded '{}' ({} bytes, {} blocks): download={}ms, decode={}ms, handoff={}ms",
                            build.name(), bytes.length, prepared.blockRegion().count(),
                            (downloaded-started)/1_000_000, (decoded-downloaded)/1_000_000, (System.nanoTime()-decoded)/1_000_000);
                });
            } catch (Exception | LinkageError e) {
                SchematioAxiomClient.LOG.warn("Axiom import failed", e);
                mc.execute(() -> {
                    if (!guard.isCurrent(ticket)) return;
                    busy = false; status = message(e);
                });
            }
        });
    }

    void cancel() {
        guard.cancel(); searchGeneration++;
        if (pending != null) pending.cancel(true);
        busy = false; status = "Cancelled. Your Axiom clipboard is unchanged.";
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? "Transfer failed. See the game log for details." : e.getMessage();
    }
}
