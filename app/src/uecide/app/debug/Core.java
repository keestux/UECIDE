
/* -*- mode: jde; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
  Core - represents a hardware platform
  Part of the Arduino project - http://www.arduino.cc/

  Copyright (c) 2009 David A. Mellis

  This program is free software; you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation; either version 2 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software Foundation,
  Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
  
  $Id$
*/

package uecide.app.debug;

import java.io.*;
import java.util.*;

import uecide.app.Preferences;
import uecide.app.Base;
import uecide.app.Sketch;
import uecide.app.SketchFile;
import uecide.app.SketchCode;
import java.text.MessageFormat;




import org.apache.log4j.BasicConfigurator;
//import org.apache.log4j.PropertyConfigurator;
import org.apache.log4j.Logger;
import org.apache.log4j.Level;


public class Core implements MessageConsumer {
    private String name;
    private File folder;
    public Map corePreferences;
    private boolean valid;
    private File api;
    private boolean runInVerboseMode;

    static Logger logger = Logger.getLogger(Base.class.getName());
  
    public Core(File folder) {
        this.folder = folder;

        File coreFile = new File(folder,"core.txt");

        valid = false;

        try {
            if(coreFile.exists()) {
                corePreferences = new LinkedHashMap();
                Preferences.load(new FileInputStream(coreFile), corePreferences);
                this.name = folder.getName();
                this.api = new File(folder, get("library.core.path","api"));
            }
            valid = true;
        } catch (Exception e) {
            System.err.println("Error loading core from " + coreFile + ": " + e);
        }

    }

    public File getLibraryFolder()
    {
        File lf = new File(folder, get("library.path", "libraries"));
        return lf;
    }

    public String getName() { 
        return name; 
    }

    public File getFolder() { 
        return folder; 
    }

    public File getAPIFolder() {
        return api;
    }

    public boolean isValid() {
        return valid;
    }

    public void message(String m) {
        message(m, 1);
    }

    public void message(String m, int chan) {
        if (m.trim() != "") {
            if (chan == 2) {
                System.err.print(m);
            } else {
                System.out.print(m);
            }
        }
    }

    public String get(String k) {
        return (String) corePreferences.get(k);
    }

    public String get(String k, String d) {
        if (get(k) == null) {
            return d;
        }
        return get(k);
    }

    static private boolean createFolder(File folder) {
        if (folder.isDirectory())
            return false;
        if (!folder.mkdir())
            return false;
        return true;
    }

    static public String[] headerListFromIncludePath(String path) {
        FilenameFilter onlyHFiles = new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.endsWith(".h");
            }
        };

        return (new File(path)).list(onlyHFiles);
    }

}
