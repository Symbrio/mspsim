/**
 * Copyright (c) 2007, Swedish Institute of Computer Science.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 *
 * This file is part of MSPSim.
 *
 * $Id$
 *
 * -----------------------------------------------------------------
 *
 * GenericNode
 *
 * Author  : Joakim Eriksson
 */

package se.sics.mspsim.platform;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;

import se.sics.mspsim.cli.CommandHandler;
import se.sics.mspsim.cli.DebugCommands;
import se.sics.mspsim.cli.MiscCommands;
import se.sics.mspsim.cli.ProfilerCommands;
import se.sics.mspsim.cli.StreamCommandHandler;
import se.sics.mspsim.cli.WindowCommands;
import se.sics.mspsim.core.Chip;
import se.sics.mspsim.core.MSP430;
import se.sics.mspsim.core.MSP430Constants;
import se.sics.mspsim.extutil.highlight.HighlightSourceViewer;
import se.sics.mspsim.ui.ControlUI;
import se.sics.mspsim.util.ArgumentManager;
import se.sics.mspsim.util.ComponentRegistry;
import se.sics.mspsim.util.ConfigManager;
import se.sics.mspsim.util.ELF;
import se.sics.mspsim.util.IHexReader;
import se.sics.mspsim.util.MapTable;
import se.sics.mspsim.util.OperatingModeStatistics;
import se.sics.mspsim.util.StatCommands;

public abstract class GenericNode extends Chip implements Runnable {

  protected ConfigManager config;

  protected ComponentRegistry registry = new ComponentRegistry();
  protected MSP430 cpu = new MSP430(0);
  protected String firmwareFile = null;
  protected ELF elf;
  protected OperatingModeStatistics stats;

  public ComponentRegistry getRegistry() {
    return registry;
  }

  public MSP430 getCPU() {
    return cpu;
  }

  public abstract void setupNode();

  public void setCommandHandler(CommandHandler handler) {
    registry.registerComponent("commandHandler", handler);
  }

  public void setupArgs(ArgumentManager config) throws IOException {
    String[] args = config.getArguments();
    if (args.length == 0) {
      System.out.println("Usage: " + getClass().getName() + " <firmware>");
      System.exit(1);
    }
    firmwareFile = args[0];

    int[] memory = cpu.getMemory();
    if (args[0].endsWith("ihex")) {
      // IHEX Reading
      IHexReader reader = new IHexReader();
      reader.readFile(memory, firmwareFile);
    } else {
      loadFirmware(firmwareFile, memory);
    }
    if (args.length > 1) {
      MapTable map = new MapTable(args[1]);
      cpu.getDisAsm().setMap(map);
      registry.registerComponent("mapTable", map);
    }
    
    setup(config);


    if (!config.getPropertyAsBoolean("nogui", false)) {
      // Setup control and other UI components
      ControlUI control = new ControlUI(registry);
      HighlightSourceViewer sourceViewer = new HighlightSourceViewer();
//    sourceViewer.addSearchPath(new File("../../contiki-2.x/examples/energest-demo/"));
      control.setSourceViewer(sourceViewer);
    }

    System.out.println("-----------------------------------------------");
    System.out.println("MSPSim " + MSP430Constants.VERSION + " starting firmware: " + firmwareFile);
    System.out.println("-----------------------------------------------");
    
    String script = config.getProperty("autorun");
    if (script != null) {
      File fp = new File(script);
      if (fp.canRead()) {
        CommandHandler ch = (CommandHandler) registry.getComponent("commandHandler");
        System.out.println("Autoloading script: " + script);
        if (ch != null) {
          ch.lineRead("source " + script);
        }
      }
    }
  }

  public void setup(ConfigManager config) throws IOException {
    this.config = config;
    registry.registerComponent("cpu", cpu);
    registry.registerComponent("node", this);
    registry.registerComponent("config", config);

    CommandHandler ch = (CommandHandler) registry.getComponent("commandHandler");
    if (ch == null) {
      ch = new StreamCommandHandler(System.in, System.out, System.err);
      registry.registerComponent("commandHandler", ch);
    }
    stats = new OperatingModeStatistics(cpu);
    registry.registerComponent("debugcmd", new DebugCommands());
    registry.registerComponent("misccmd", new MiscCommands());
    registry.registerComponent("statcmd", new StatCommands(cpu, stats));
    registry.registerComponent("wincmd", new WindowCommands());
    registry.registerComponent("profilecmd", new ProfilerCommands());

    // Monitor execution
    cpu.setMonitorExec(true);
    
    setupNode();

    registry.start();

    cpu.reset();
  }
  
 
  public void run() {
    if (!cpu.isRunning()) {
      System.out.println("Starting new CPU thread...");
      cpu.cpuloop();
      System.out.println("Stopping CPU thread...");
    }
  }
  public void start() {
    if (!cpu.isRunning()) {
      new Thread(this).start();
    }
  }
  
  public void stop() {
    cpu.setRunning(false);
  }
  
  public void step() {
    if (!cpu.isRunning()) {
      cpu.step();
    }
  }

  public ELF loadFirmware(URL url, int[] memory) throws IOException {
    DataInputStream inputStream = new DataInputStream(url.openStream());
    ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
    byte[] firmwareData = new byte[2048];
    int read;
    while ((read = inputStream.read(firmwareData)) != -1) {
      byteStream.write(firmwareData, 0, read);
    }
    inputStream.close();
    ELF elf = new ELF(byteStream.toByteArray());
    elf.readAll();
    return loadFirmware(elf, memory);
  }

  public ELF loadFirmware(String name, int[] memory) throws IOException {
    return loadFirmware(ELF.readELF(firmwareFile = name), memory);
  }

  public ELF loadFirmware(ELF elf, int[] memory) {
    stop();
    this.elf = elf;
    elf.loadPrograms(memory);
    MapTable map = elf.getMap();
    cpu.getDisAsm().setMap(map);
    cpu.setMap(map);
    registry.registerComponent("elf", elf);
    registry.registerComponent("mapTable", map);
    return elf;
  }
  
  // A step that will break out of breakpoints!
  public void step(int nr) {
    if (!cpu.isRunning()) {
      cpu.stepInstructions(nr);
    }
  }

}
