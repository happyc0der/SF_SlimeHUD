package io.github.schntgaispock.slimehud.waila;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.github.schntgaispock.slimehud.SlimeHUD;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.core.attributes.MachineProcessHolder;
import io.github.thebusybiscuit.slimefun4.core.machines.MachineOperation;
import io.github.thebusybiscuit.slimefun4.core.machines.MachineProcessor;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** Verifies machine/addon behavior independently of native and WIT display transports. */
class HudControllerTest {
    public interface GeneratorOutput {
        int getEnergyProduction();
    }

    private final YamlConfiguration config = new YamlConfiguration();
    private MockedStatic<SlimeHUD> pluginAccess;
    private HudController controller;

    @BeforeEach
    void configurePlugin() {
        SlimeHUD plugin = mock(SlimeHUD.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        pluginAccess = mockStatic(SlimeHUD.class);
        pluginAccess.when(SlimeHUD::getInstance).thenReturn(plugin);
        controller = new HudController();
    }

    @AfterEach
    void restorePluginAccess() {
        if (pluginAccess != null) {
            pluginAccess.close();
        }
    }

    @Test
    void addonHandlerTakesPriorityAndRecoversAfterTemporaryFailure() {
        SlimefunItem item = poweredItem(false);
        boolean[] fail = {false};
        controller.registerCustomHandler(EnergyNetComponent.class, request -> {
            if (fail[0]) {
                throw new IllegalStateException("Temporary addon failure");
            }
            return "Stored Diamonds: 64";
        });
        HudRequest request = request(item);

        assertEquals("Stored Diamonds: 64", controller.processRequest(request));
        fail[0] = true;
        assertEquals("250/1K J", controller.processRequest(request));
        fail[0] = false;
        assertEquals("Stored Diamonds: 64", controller.processRequest(request));
    }

    @Test
    void nonProcessingGeneratorShowsOutputAndStoredEnergyIndependently() {
        SlimefunItem item = poweredItem(true);
        when(((GeneratorOutput) item).getEnergyProduction()).thenReturn(32);
        HudRequest request = request(item);

        String all = controller.processRequest(request);
        assertTrue(all.contains("32 J/t"));
        assertTrue(all.contains("250/1K J"));

        config.set("waila.show-generator-generation", false);
        assertEquals("250/1K J", controller.processRequest(request));
        config.set("waila.show-generator-generation", true);
        config.set("waila.show-energy-stored", false);
        String outputOnly = controller.processRequest(request);
        assertTrue(outputOnly.contains("32 J/t"));
        assertFalse(outputOnly.contains("250/1K J"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void machineShowsIdleThenProgressWithoutSuppressingEnergy() {
        SlimefunItem item = mock(SlimefunItem.class,
                withSettings().extraInterfaces(MachineProcessHolder.class, EnergyNetComponent.class));
        when(item.getId()).thenReturn("TEST_MACHINE");
        EnergyNetComponent energy = (EnergyNetComponent) item;
        when(energy.getCapacity()).thenReturn(1000);
        when(energy.getCharge(any(Location.class))).thenReturn(250);
        MachineProcessor<MachineOperation> processor = mock(MachineProcessor.class);
        when(((MachineProcessHolder<MachineOperation>) item).getMachineProcessor()).thenReturn(processor);
        HudRequest request = request(item);

        assertEquals("Idle &7| 250/1K J", controller.processRequest(request));
        MachineOperation operation = mock(MachineOperation.class);
        when(operation.getProgress()).thenReturn(5);
        when(operation.getTotalTicks()).thenReturn(10);
        when(processor.getOperation(any(Location.class))).thenReturn(operation);
        assertTrue(controller.processRequest(request).contains("50%"));
        config.set("waila.show-machine-progress", false);
        assertEquals("250/1K J", controller.processRequest(request));
    }

    private SlimefunItem poweredItem(boolean generator) {
        SlimefunItem item = mock(SlimefunItem.class,
                withSettings().extraInterfaces(EnergyNetComponent.class, GeneratorOutput.class));
        when(item.getId()).thenReturn("TEST_ENERGY_BLOCK");
        EnergyNetComponent energy = (EnergyNetComponent) item;
        when(energy.getEnergyComponentType()).thenReturn(generator
                ? EnergyNetComponentType.GENERATOR : EnergyNetComponentType.CAPACITOR);
        when(energy.getCapacity()).thenReturn(1000);
        when(energy.getCharge(any(Location.class))).thenReturn(250);
        return item;
    }

    private HudRequest request(SlimefunItem item) {
        return new HudRequest(item, new Location(null, 0, 64, 0), null);
    }
}
