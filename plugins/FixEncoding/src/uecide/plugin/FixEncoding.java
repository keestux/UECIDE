/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
  Part of the Processing project - http://processing.org

  Copyright (c) 2008 Ben Fry and Casey Reas

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
*/

package uecide.plugin;

import java.io.BufferedReader;
import java.net.*;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;


import javax.swing.JOptionPane;

import uecide.app.*;


public class FixEncoding extends BasePlugin {

  public String getMenuTitle() {
    return "Fix Encoding & Reload";
  }
  
  public void run() {
    Sketch sketch = editor.getSketch();
    //SketchCode code = sketch.current;
    
    if (sketch.isModified()) {
      int result = 
        JOptionPane.showConfirmDialog(editor, 
                                      "Discard all changes and reload sketch?", 
                                      "Fix Encoding & Reload",
                                      JOptionPane.YES_NO_OPTION,
                                      JOptionPane.QUESTION_MESSAGE);

      if (result == JOptionPane.NO_OPTION) {
        return;
      }
    }
    try {
        for (SketchFile code : editor.getSketch().sketchFiles) {
            code.setText(loadWithLocalEncoding(code.file));
        }
    } catch (IOException e) {
      String msg = 
        "An error occurred while trying to fix the file encoding.\n" +
        "Do not attempt to save this sketch as it may overwrite\n" +
        "the old version. Use Open to re-open the sketch and try again.\n" +
        e.getMessage();
      Base.showWarning("Fix Encoding & Reload", msg, e); 
    }
  }


  protected String loadWithLocalEncoding(File file) throws IOException {
    // FileReader uses the default encoding, which is what we want.
    FileReader fr = new FileReader(file);
    BufferedReader reader = new BufferedReader(fr);

    StringBuffer buffer = new StringBuffer();
    String line = null;
    while ((line = reader.readLine()) != null) {
      buffer.append(line);
      buffer.append('\n');
    }
    reader.close();
    return buffer.toString();
  }
}
