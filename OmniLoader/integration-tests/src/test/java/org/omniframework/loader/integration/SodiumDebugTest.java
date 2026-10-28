package org.omniframework.loader.integration;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.omniframework.loader.bootstrap.*;
import org.omniframework.loader.compat.fabric.FabricCompatLayer;
import org.omniframework.loader.compat.neoforge.NeoForgeCompatLayer;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.model.*;
import org.omniframework.loader.resolve.*;
import org.omniframework.loader.provider.*;

class SodiumDebugTest {
  @Test
  void whySodiumFails() throws Exception {
    Path mods = Path.of(System.getenv("APPDATA"), "OmniLauncher", "instances", "4", "game", "mods");
    IssueCollector issues = new IssueCollector();
    var layers = List.of(new FabricCompatLayer(), new NeoForgeCompatLayer());
    DiscoveredMods d = ModDiscovery.discover(mods, layers, issues);
    System.out.println("loadable=" + d.allLoadable().size());
    Optional<ModCandidate> sodium = d.allLoadable().stream().filter(c -> c.id().equals("sodium")).findFirst();
    System.out.println("sodium present=" + sodium.isPresent() + " nested=" + sodium.map(ModCandidate::isNested).orElse(null));
    if (sodium.isPresent()) {
      System.out.println("sodium ver=" + sodium.get().version());
      for (Dependency dep : sodium.get().metadata().dependencies()) {
        System.out.println("  dep " + dep.kind() + " " + dep.modId() + " " + dep.versions());
      }
    }
    // Find who conflicts with sodium 0.6.13
    ModVersion sv = ModVersion.parse("0.6.13+mc1.21.1");
    for (ModCandidate c : d.allLoadable()) {
      for (Dependency dep : c.metadata().dependencies()) {
        if (dep.kind().isNegative() && dep.modId().equals("sodium") && dep.matches(sv)) {
          System.out.println("BREAKS sodium: " + c.id() + " " + c.version() + " range=" + dep.versions());
        }
        if (dep.kind() == Dependency.Kind.DEPENDS && dep.modId().equals("sodium") && !dep.matches(sv)) {
          System.out.println("DEPENDS sodium fail: " + c.id() + " needs " + dep.versions() + " vs " + sv);
        }
      }
    }
    // sodium breaks present mods?
    if (sodium.isPresent()) {
      for (Dependency dep : sodium.get().metadata().dependencies()) {
        if (!dep.kind().isNegative()) continue;
        for (ModCandidate c : d.allLoadable()) {
          if (c.id().equals(dep.modId()) || c.metadata().provides().stream().anyMatch(p -> p.id().equals(dep.modId()))) {
            boolean m = dep.matches(c.version());
            if (m) System.out.println("SODIUM BREAKS installed: " + c.id() + " " + c.version() + " range=" + dep.versions());
          }
        }
      }
    }
  }
}
