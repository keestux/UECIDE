/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

package uecide.app;

import uecide.app.debug.*;
import uecide.app.preproc.*;
import uecide.plugin.*;

import java.util.regex.*;

import jssc.*;

import java.awt.*;
import java.awt.event.*;
import java.beans.*;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.zip.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;

/**
 * Stores information about files in the current sketch
 */
public class Sketch implements MessageConsumer {
    public String name;     // The name of the sketch
    public File folder;
    public Editor editor;
    public File buildFolder;
    public String uuid; // used for temporary folders etc

    PrintWriter stdoutRedirect = null;
    PrintWriter stderrRedirect = null;

    public ArrayList<SketchFile> sketchFiles = new ArrayList<SketchFile>();

    public HashMap<String, Library> importedLibraries = new HashMap<String, Library>();

    public HashMap<String, String> settings = new HashMap<String, String>();
    public HashMap<String, String> parameters = new HashMap<String, String>();

    public Sketch(Editor ed, String path) {
        this(ed, new File(path));
    }

    public Sketch(Editor ed, File path) {
        editor = ed;
        uuid = UUID.randomUUID().toString();

        buildFolder = createBuildFolder();
        if (path == null) {
            path = createUntitledSketch();
        }
        folder = path;
        if (!path.exists()) {
            path.mkdirs();
            createBlankFile(path.getName() + ".ino");
        }

        String fn = path.getName().toLowerCase();
        if (fn.endsWith(".ino") || fn.endsWith(".pde")) {
            path = path.getParentFile();
        }
        folder = path;
        name = folder.getName();
        loadSketchFromFolder();
        editor.setTitle(Base.theme.get("product.cap") + " | " + name);
    }

    public void createBlankFile(String fileName) {
        File f = new File(folder, fileName);
        if (f.exists()) {
            return;
        }
        try {
            f.createNewFile();
        } catch (Exception e) {
        }
    }

    public void loadSketchFromFolder() {
        if (!isUntitled()) {
            Base.updateMRU(folder);
        }
        File fileList[] = folder.listFiles();
        Arrays.sort(fileList);

        for (File f : fileList){
            if (f.getName().endsWith(".ino")) {
                loadFile(f);
            }
        }

        for (File f : fileList){
            if (f.getName().endsWith(".pde")) {
                loadFile(f);
            }
        }

        for (File f : fileList){
            if (f.getName().endsWith(".cpp")) {
                loadFile(f);
            }
        }

        for (File f : fileList){
            if (f.getName().endsWith(".c")) {
                loadFile(f);
            }
        }

        for (File f : fileList){
            if (f.getName().endsWith(".S")) {
                loadFile(f);
            }
        }

        for (File f : fileList){
            if (f.getName().endsWith(".h")) {
                loadFile(f);
            }
        }


    }

    public boolean loadFile(File f) {
        if (!f.exists()) {
            return false;
        }

        SketchFile newFile = new SketchFile();
        newFile.file = f;
        newFile.textArea = editor.addTab(f);
        newFile.modified = false;
        sketchFiles.add(newFile);
        return true;
    }

    public SketchFile getFileByName(String filename) {
        for (SketchFile f : sketchFiles) {
            if (f.file.getName().equals(filename)) {
                return f;
            }   
        }
        return null;
    }

    public File createBuildFolder() {
        String name = "build-" + uuid;
        File f = new File(Base.getTmpDir(), name);
        if (!f.exists()) {
            f.mkdirs();
        }
        f.deleteOnExit();
        return f;
    }

    public File createUntitledSketch() {

        int num = 0;
        File f = null;
        do {
            num++;
            String name = "untitled" + Integer.toString(num);
            f = new File(Base.getTmpDir(), name);
        } while (f.exists());
        f.deleteOnExit();
        return f;
    }

    public SketchFile getMainFile() {
        SketchFile f = getFileByName(name + ".ino");
        if (f == null) {
            f = getFileByName(name + ".pde");
        }
        return f;
    }

    public String getMainFilePath() {
        SketchFile f = getMainFile();
        if (f == null) {
            return "";
        }
        return f.file.getAbsolutePath();
    }

    public boolean isModified() {
        boolean modified = false;
        for (SketchFile f : sketchFiles) {
            if (f.textArea.isModified()) {
                modified = true;
            }
        }
        return modified;
    }

    static public boolean isSanitaryName(String name) {
        return sanitizeName(name).equals(name);
    }

    static public String sanitizeName(String origName) {
        char c[] = origName.toCharArray();
        StringBuffer buffer = new StringBuffer();

        for (int i = 0; i < c.length; i++) {
            if (((c[i] >= '0') && (c[i] <= '9')) ||
                ((c[i] >= 'a') && (c[i] <= 'z')) ||
                ((c[i] >= 'A') && (c[i] <= 'Z'))) {
                buffer.append(c[i]);
            } else {
                buffer.append('_');
            }
        }
        if (buffer.length() > 63) {
            buffer.setLength(63);
        }
        return buffer.toString();
    }

    public File getFolder() {
        return folder;
    }

    public boolean prepare() {
        HashMap<String, String> all = mergeAllProperties();
        if (editor.board == null) {
            Base.showWarning(Translate.t("No Board Selected"), Translate.w("You have no board selected.  You must select a board before you can compile your sketch.", 40, "\n"), null);
            return false;
        }
        if (editor.core == null) {
            Base.showWarning(Translate.t("No Core Selected"), Translate.w("You have no core selected.  You must select a core before you can compile your sketch.", 40, "\n"), null);
            return false;
        }
        if (Base.preferences.getBoolean("export.delete_target_folder")) {
            cleanBuild();
        }
        parameters = new HashMap<String, String>();
        importedLibraries = new HashMap<String, Library>();
        StringBuilder combinedMain = new StringBuilder();
        SketchFile mainFile = getMainFile();
        if (Base.preferences.getBoolean("compiler.combine_ino")) {
            combinedMain.append("#line 1 \"" + mainFile.file.getName() + "\"\n");
            Pattern pragma = Pattern.compile("#pragma\\s+parameter");
            String[] bodylines = mainFile.textArea.getText().split("\n");
            for (String line : bodylines) {
                if (line.trim().startsWith("#pragma")) {
                    Matcher mtch = pragma.matcher(line);
                    if (mtch.find()) {
                        line = "// " + line;
                    }
                }
                combinedMain.append(line + "\n");
            }
        }
        for (SketchFile f : sketchFiles) {
            String lcn = f.file.getName().toLowerCase();
            if (
                lcn.endsWith(".h") || 
                lcn.endsWith(".hh") || 
                lcn.endsWith(".c") || 
                lcn.endsWith(".cpp") || 
                lcn.endsWith(".s")
            ) {
                f.writeToFolder(buildFolder);
            }
            if (lcn.endsWith(".pde") || lcn.endsWith(".ino")) {

                if (Base.preferences.getBoolean("compiler.combine_ino")) {
                    if (!(f.equals(mainFile))) {
                        combinedMain.append("#line 1 \"" + f.file.getName() + "\"\n");
                        Pattern pragma = Pattern.compile("#pragma\\s+parameter");
                        String[] bodylines = mainFile.textArea.getText().split("\n");
                        for (String line : bodylines) {
                            if (line.trim().startsWith("#pragma")) {
                                Matcher mtch = pragma.matcher(line);
                                if (mtch.find()) {
                                    line = "// " + line;
                                }
                            }
                            combinedMain.append(line + "\n");
                        }
                    }
                } else {
                    String rawData = f.textArea.getText();
                    PdePreprocessor proc = new PdePreprocessor();

                    f.includes = gatherIncludes(f);
                    f.prototypes = proc.prototypes(rawData).toArray(new String[0]);
                    StringBuilder sb = new StringBuilder();

                    f.headerLines = 0;
                    int firstStat = proc.firstStatement(rawData);

                    String header = rawData.substring(0, firstStat);
                    String body = rawData.substring(firstStat);

                    Matcher m = Pattern.compile("(\n)|(\r)|(\r\n)").matcher(header);
                    int headerLength = 1;
                    while (m.find())
                    {
                        headerLength++;
                    }

                    String coreHeader = all.get("core.header");
                    if (coreHeader != "") {
                        sb.append("#include <" + coreHeader + ">\n");
                        f.headerLines ++;
                    }
                    sb.append("#line 1 \"" + f.file.getName() + "\"\n");
                    sb.append(header);
                    f.headerLines += headerLength;

                    if (Base.preferences.getBoolean("compiler.disable_prototypes") == false) {
                        for (String prototype : f.prototypes) {
                            sb.append(prototype + "\n");
                            f.headerLines++;
                        }
                    }

                    sb.append("\n");
                    f.headerLines ++;

                    sb.append("#line " + headerLength + " \"" + f.file.getName() + "\"\n");
                    f.headerLines ++;

                    Pattern pragma = Pattern.compile("#pragma\\s+parameter");
                    String[] bodylines = body.split("\n");
                    for (String line : bodylines) {
                        if (line.trim().startsWith("#pragma")) {
                            Matcher mtch = pragma.matcher(line);
                            if (mtch.find()) {
                                line = "// " + line;
                            }
                        }
                        sb.append(line + "\n");
                    }

                    String newFileName = f.file.getName();
                    int dot = newFileName.lastIndexOf(".");
                    newFileName = newFileName.substring(0, dot);
                    String ext = all.get("build.extension"); 
                    if (ext == null) ext = "cpp";
                    newFileName = newFileName + "." + ext;

                    try {
                        PrintWriter pw = new PrintWriter(new File(buildFolder, newFileName));
                        pw.print(sb.toString());
                        pw.close();
                    } catch (Exception e) {
                        Base.error(e);
                    }
                }
            }
            if (Base.preferences.getBoolean("editor.correct_numbers")) {
                f.textArea.setNumberOffset(f.headerLines+1);
            } else {
                f.textArea.setNumberOffset(1);
            }
        }
        if (Base.preferences.getBoolean("compiler.combine_ino")) {
            SketchFile f = getMainFile();
            String rawData = combinedMain.toString();
            PdePreprocessor proc = new PdePreprocessor();

            f.includes = gatherIncludes(f);
            f.prototypes = proc.prototypes(rawData).toArray(new String[0]);
            StringBuilder sb = new StringBuilder();
            f.headerLines = 0;
            int firstStat = proc.firstStatement(rawData);

            String header = rawData.substring(0, firstStat);
            String body = rawData.substring(firstStat);

            Matcher m = Pattern.compile("(\n)|(\r)|(\r\n)").matcher(header);
            int headerLength = 1;
            while (m.find())
            {
                headerLength++;
            }

            String coreHeader = all.get("core.header");
            if (coreHeader != "") {
                sb.append("#include <" + coreHeader + ">\n");
                f.headerLines ++;
            }
            sb.append("#line 1 \"" + f.file.getName() + "\"\n");
            sb.append(header);
            f.headerLines += headerLength;

            if (Base.preferences.getBoolean("compiler.disable_prototypes") == false) {
                for (String prototype : f.prototypes) {
                    sb.append(prototype + "\n");
                    f.headerLines++;
                }
            }

            sb.append("\n");
            f.headerLines ++;

            sb.append("#line " + headerLength + " \"" + f.file.getName() + "\"\n");
            f.headerLines ++;
            Pattern pragma = Pattern.compile("#pragma\\s+parameter");
            String[] bodylines = body.split("\n");
            for (String line : bodylines) {
                if (line.trim().startsWith("#pragma")) {
                    Matcher mtch = pragma.matcher(line);
                    if (mtch.find()) {
                        line = "// " + line;
                    }
                }
                sb.append(line + "\n");
            }

            String ext = all.get("build.extension"); 
            if (ext == null) ext = "cpp";
            String newFileName = name + "." + ext;

            try {
                PrintWriter pw = new PrintWriter(new File(buildFolder, newFileName));
                pw.print(sb.toString());
                pw.close();
            } catch (Exception e) {
                Base.error(e);
            }
        }
        return true;
    }

    public String stripComments(String data) {
        StringBuilder b = new StringBuilder();

        String[] lines = data.split("\n");
        for (String line : lines) {
            int comment = line.indexOf("//");
            if (comment > -1) {
                line = line.substring(0, comment);
            }
            b.append(line);
            b.append("\n");
        }

        String out = b.toString();

        out.replaceAll("/\\*(?:.|[\\n\\r])*?\\*/", "");

        return out;
    }

    public String[] gatherIncludes(SketchFile f) {
        String[] data = f.textArea.getText().split("\n"); //stripComments(f.textArea.getText()).split("\n");
        ArrayList<String> includes = new ArrayList<String>();
    
        Pattern pragma = Pattern.compile("#pragma\\s+parameter\\s+([^=]+)\\s*=\\s*(.*)");

        for (String line : data) {
            line = line.trim();
            if (line.startsWith("#pragma")) {
                Matcher m = pragma.matcher(line);
                if (m.find()) {
                    parameters.put(m.group(1), m.group(2).replaceAll(" ", "::"));
                }
                continue;
            }
            if (line.startsWith("#include")) {
                int qs = line.indexOf("<");
                if (qs == -1) {
                    qs = line.indexOf("\"");
                }
                if (qs == -1) {
                    continue;
                }
                qs++;
                int qe = line.indexOf(">");
                if (qe == -1) {
                    qe = line.indexOf("\"", qs);
                }
                String i = line.substring(qs, qe);
                addLibraryToImportList(i);
            }
        }
        return includes.toArray(new String[includes.size()]);
    }

    public void addLibraryToImportList(String l) {
        if (l.endsWith(".h")) {
            l = l.substring(0, l.lastIndexOf("."));
        }
        HashMap<String, Library> globalLibraries = Base.getLibraryCollection("global");
        HashMap<String, Library> coreLibraries = Base.getLibraryCollection(editor.core.getName());
        HashMap<String, Library> contribLibraries = Base.getLibraryCollection("sketchbook");

        if (importedLibraries.get(l) != null) {
            // The library has already been imported.  Do nothing
            return;
        }

        Library lib = globalLibraries.get(l);
        if (lib == null) {
            lib = coreLibraries.get(l);
        }
        if (lib == null) {
            lib = contribLibraries.get(l);
        }

        if (lib == null) {
            // The library doesn't exist - either it's a system header or a library that isn't installed.
            return;
        }

        // At this point we have a valid library that hasn't yet been imported.  Now to recurse.
        // First add the library to the imported list
        importedLibraries.put(l, lib);

        // And then work through all the required libraries and add them.
        ArrayList<String> requiredLibraries = lib.getRequiredLibraries();
        for (String req : requiredLibraries) {
            addLibraryToImportList(req);
        }
    }

    public SketchFile getCodeByEditor(SketchEditor e) {
        for (SketchFile f : sketchFiles) {
            if (f.textArea.equals(e)) {
                return f;
            }   
        }
        return null;
    }

    public boolean upload() {
        String uploadCommand;
        if (!build()) {
            return false;
        }

        HashMap<String, String> all = mergeAllProperties();
        editor.statusNotice(Translate.t("Uploading to Board..."));
        settings.put("filename", name);
        settings.put("filename.elf", name + ".elf");
        settings.put("filename.hex", name + ".hex");
        settings.put("filename.eep", name + ".eep");

        boolean isJava = true;
        uploadCommand = editor.board.get("upload." + editor.programmer + ".command.java");
        if (uploadCommand == null) {
            uploadCommand = editor.core.get("upload." + editor.programmer + ".command.java");
        }
        if (uploadCommand == null) {
            isJava = false;
            uploadCommand = editor.board.get("upload." + editor.programmer + ".command." + Base.getOSFullName());
        }
        if (uploadCommand == null) {
            uploadCommand = editor.board.get("upload." + editor.programmer + ".command." + Base.getOSName());
        }
        if (uploadCommand == null) {
            uploadCommand = editor.board.get("upload." + editor.programmer + ".command");
        }
        if (uploadCommand == null) {
            uploadCommand = editor.core.get("upload." + editor.programmer + ".command." + Base.getOSFullName());
        }
        if (uploadCommand == null) {
            uploadCommand = editor.core.get("upload." + editor.programmer + ".command." + Base.getOSName());
        }
        if (uploadCommand == null) {
            uploadCommand = editor.core.get("upload." + editor.programmer + ".command");
        }

        if (uploadCommand == null) {
            message("No upload command defined for board\n", 2);
            editor.statusNotice(Translate.t("Upload Failed"));
            return false;
        }

   
        if (isJava) {
            Plugin uploader;
            uploader = Base.plugins.get(uploadCommand);
            if (uploader == null) {
                message("Upload class " + uploadCommand + " not found.\n", 2);
                editor.statusNotice(Translate.t("Upload Failed"));
                return false;
            }
            try {
                if ((uploader.flags() & BasePlugin.LOADER) == 0) {
                    message(uploadCommand + "is not a valid loader plugin.\n", 2);
                    editor.statusNotice(Translate.t("Upload Failed"));
                    return false;
                }
                uploader.run();
            } catch (Exception e) {
                editor.statusNotice(Translate.t("Upload Failed"));
                message(e.toString(), 2);
                return false;
            }
            editor.statusNotice(Translate.t("Upload Complete"));
            return true;
        }

        String[] spl;
        spl = parseString(uploadCommand).split("::");

        String executable = spl[0];
        if (Base.isWindows()) {
            executable = executable + ".exe";
        }

        File exeFile = new File(folder, executable);
        File tools;
        if (!exeFile.exists()) {
            tools = new File(folder, "tools");
            exeFile = new File(tools, executable);
        }
        if (!exeFile.exists()) {
            exeFile = new File(editor.core.getFolder(), executable);
        }
        if (!exeFile.exists()) {
            tools = new File(editor.core.getFolder(), "tools");
            exeFile = new File(tools, executable);
        }
        if (!exeFile.exists()) {
            exeFile = new File(executable);
        }
        if (exeFile.exists()) {
            executable = exeFile.getAbsolutePath();
        }

        spl[0] = executable;

        // Parse each word, doing String replacement as needed, trimming it, and
        // generally getting it ready for executing.

        String commandString = executable;
        for (int i = 1; i < spl.length; i++) {
            String tmp = spl[i];
            tmp = tmp.trim();
            if (tmp.length() > 0) {
                commandString += "::" + tmp;
            }
        }

        boolean dtr = false;
        boolean rts = false;


        String ulu = all.get("upload." + editor.programmer + ".using");
        if (ulu == null) ulu = "serial";

        String doDtr = all.get("upload." + editor.programmer + ".dtr");
        if (doDtr != null) {
            if (doDtr.equals("yes")) {
                dtr = true;
            }
        }
        String doRts = all.get("upload." + editor.programmer + ".rts");
        if (doRts != null) {
            if (doRts.equals("yes")) {
                rts = true;
            }
        }

        if (ulu.equals("serial") || ulu.equals("usbcdc"))
        {
            editor.grabSerialPort();
            if (dtr || rts) {
                assertDTRRTS(dtr, rts);
            }
        }
        if (ulu.equals("usbcdc")) {
            try {
                String baud = all.get("upload." + editor.programmer + ".reset.baud");
                if (baud != null) {
                    System.err.println("Opening serial port at " + baud + " baud");
                    int b = Integer.parseInt(baud);
          //          editor.grabSerialPort();
                    
                    SerialPort serialPort = Serial.requestPort(editor.getSerialPort(), this, b);
                    if (serialPort == null) {
                        Base.error("Unable to lock serial port");
                        return false;
                    }
                    Thread.sleep(1000);
                    Serial.releasePort(serialPort);
                    serialPort = null;
                    System.gc();
                    Thread.sleep(1500);
                }
            } catch (Exception e) {
                Base.error(e);
            }
        }

        boolean res = execAsynchronously(commandString);
        if (ulu.equals("serial") || ulu.equals("usbcdc"))
        {
            if (dtr || rts) {
                assertDTRRTS(false, false);
            }
            editor.releaseSerialPort();
        }
        if (res) {
            editor.statusNotice(Translate.t("Upload Complete"));
        } else {
            editor.statusNotice(Translate.t("Upload Failed"));
        }
        return res;
    }

    public void assertDTRRTS(boolean dtr, boolean rts) {
        System.err.println("Asserting DTR...");
        try {
            SerialPort serialPort = Serial.requestPort(editor.getSerialPort(), this);
            if (serialPort == null) {
                Base.error("Unable to lock serial port");
            }
            serialPort.setDTR(dtr);
            serialPort.setRTS(rts);
            Serial.releasePort(serialPort);
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }
        System.gc();
    }

    public boolean build() {
        if (editor.board == null) {
            Base.showWarning(Translate.t("No Board Selected"), Translate.w("The sketch cannot be compiled: You have no board selected. If you haven't yet installed any boards please do so through the Plugin Manager.", 40, "\n"), null);
            return false;
        }
        if (editor.core == null) {
            Base.showWarning(Translate.t("No Core Selected"), Translate.w("The sketch cannot be compiled: You have no core selected. If you haven't yet installed a core please do so through the Plugin Manager.", 40, "\n"), null);
            return false;
        }
        if (editor.compiler == null) {
            Base.showWarning(Translate.t("No Compiler Available"), Translate.w("The sketch cannot be compiled: The compiler for the selected core is not available. Please ensure the compiler is installed using the Plugin Manager.", 40, "\n"), null);
            return false;
        }
        editor.statusNotice(Translate.t("Compiling..."));
        try {
            if (!prepare()) {
                editor.statusNotice(Translate.t("Compile Failed"));
                return false;
            }
        } catch (Exception e) {
            Base.error(e);
            return false;
        }
        return compile();
    }

    public boolean save() {
        for (SketchFile f : sketchFiles) {
            f.save();
        }
        return true;
    }

    public String getName() {
        return name;
    }

    public Collection<Library> getImportedLibraries() {
        return importedLibraries.values();
    }

    public ArrayList<String> getIncludePaths() {
        ArrayList<String> libFiles = new ArrayList<String>();
      
        libFiles.add(buildFolder.getAbsolutePath());
        libFiles.add(editor.board.getFolder().getAbsolutePath());
        libFiles.add(editor.core.getAPIFolder().getAbsolutePath());
        libFiles.add(editor.core.getLibraryFolder().getAbsolutePath());

        for (Library l : getImportedLibraries()) {
            libFiles.add(l.getFolder().getAbsolutePath());
        }
        return libFiles;
    }

    public void checkForSettings() {
        SketchFile mainFile = getMainFile();

        Pattern p = Pattern.compile("^#pragma\\s+parameter\\s+([^\\s]+)\\s*=\\s*(.*)$");

        String[] data = mainFile.textArea.getText().split("\n");
        for (String line : data) {
            line = line.trim();
            Matcher m = p.matcher(line);
            if (m.find()) {
                String key = m.group(1);
                String value = m.group(2);
                if (key.equals("board")) {
                    editor.selectBoard(value);
                }
            }
        }
    }
        
    public boolean saveAs() {
        FileDialog fd = new FileDialog(editor,
                                   Translate.t("Save sketch folder as..."),
                                   FileDialog.SAVE);

        fd.setDirectory(Base.preferences.get("sketchbook.path"));

        fd.setFile(name);

        fd.setVisible(true);

        String newParentDir = fd.getDirectory();
        String newFileName = fd.getFile();

        if (newFileName == null) {
            return false;
        }

        File newFolder = new File(newParentDir, newFileName);

        if (newFolder.equals(folder)) {
            save();
            return true;
        }

        if (newFolder.exists()) {
            Object[] options = { "OK", "Cancel" };
            String prompt = "Replace " + newFolder.getAbsolutePath();
            int result = JOptionPane.showOptionDialog(editor, prompt, "Replace",
                                                JOptionPane.YES_NO_OPTION,
                                                JOptionPane.QUESTION_MESSAGE,
                                                null, options, options[0]);
            if (result != JOptionPane.YES_OPTION) {
                return false;
            }
            Base.removeDir(newFolder);
        }

        newFolder.mkdirs();
        for (SketchFile f : sketchFiles) {
            String n = f.file.getName();
            f.file = new File(newFolder, n);
        }

        SketchFile mf = getMainFile();
        mf.file = new File(newFolder, newFolder.getName() + ".ino");
        folder = newFolder;
        name = folder.getName();
        save();
        editor.setTitle(Base.theme.get("product.cap") + " | " + name);
        int index = editor.getTabByFile(mf);
        editor.setTabName(index, mf.file.getName());
        return true;
    }

    public void handleAddFile() {
        ensureExistence();

        // if read-only, give an error
        if (isReadOnly()) {
            // if the files are read-only, need to first do a "save as".
            Base.showMessage(Translate.t("Sketch is Read-Only"),
                Translate.w("Some files are marked \"read-only\", so you'll need to re-save the sketch in another location, and try again.", 40, "\n"));
            return;
        }

        // get a dialog, select a file to add to the sketch
        String prompt = Translate.t("Select a source file to copy to your sketch");
        FileDialog fd = new FileDialog(editor, prompt, FileDialog.LOAD);
        fd.setVisible(true);

        String directory = fd.getDirectory();
        String filename = fd.getFile();
        if (filename == null) return;

        // copy the file into the folder. if people would rather
        // it move instead of copy, they can do it by hand
        File sourceFile = new File(directory, filename);

        // now do the work of adding the file
        boolean result = addFile(sourceFile);

        if (result) {
            editor.statusNotice(Translate.t("One file added to the sketch."));
        }
    }

    public boolean addFile(File sourceFile) {
        String filename = sourceFile.getName();
        boolean replacement = false;

        if (!sourceFile.exists()) {
            Base.showWarning(Translate.t("Error Adding File"),Translate.w("Error: %1 does not exist", 40, "\n", sourceFile.getAbsolutePath()), null);
            return false;
        }

        if (!(validSourceFile(sourceFile))) {
            Base.showWarning(Translate.t("Error Adding File"),Translate.w("Error: you can only add .ino, .pde, .c, .cpp, .h, .hh or .S files to a sketch", 40, "\n"), null);
            return false;
        }

        File destFile = new File(folder, sourceFile.getName());

        if (sourceFile.equals(destFile)) {
            Base.showWarning(Translate.t("Error Adding File"),Translate.t("Error: that file is already in the sketch!"), null);
            return false;
        }

        if (destFile.exists()) {
            Object[] options = { "OK", "Cancel" };
            String prompt = "Replace the existing version of " + filename + "?";
            int result = JOptionPane.showOptionDialog(editor, prompt, "Replace",
                                                JOptionPane.YES_NO_OPTION,
                                                JOptionPane.QUESTION_MESSAGE,
                                                null, options, options[0]);
            if (result == JOptionPane.YES_OPTION) {
                replacement = true;
            } else {
                return false;
            }
        }

        if (replacement) {
            boolean muchSuccess = destFile.delete();
            if (!muchSuccess) {
                Base.showWarning(Translate.t("Error Adding File"), Translate.w("Could not delete the existing file %1", 40, "\n",
                    destFile.getAbsolutePath()), null);
                return false;
            }
        }

        try {
            Base.copyFile(sourceFile, destFile);
        } catch (Exception e) {
            Base.showWarning(Translate.t("Error Adding File"),Translate.w("Error copying file: %1", 40, "\n", e.getMessage()), null);
            return false;
        }

        loadFile(destFile);
        return true;
    }

    public SketchFile getCode(int i) {
        return sketchFiles.get(i);
    }

    public File getBuildFolder() {
        return buildFolder;
    }

    public String getBuildPath() {
        return buildFolder.getAbsolutePath();
    }

    public void cleanup() {
        System.gc();
        Base.removeDescendants(buildFolder);
    }

        public void setCompilingProgress(int percent) {
            editor.status.progressUpdate(percent);
        }

    public void ensureExistence() {
        if (folder.exists() && folder.isDirectory()) return;

        Base.showWarning(Translate.t("Sketch Disappeared"),
             Translate.w("The sketch folder has disappeared. Will attempt to re-save in the same location, but anything besides the code will be lost.", 40, "\n"), null);
        try {
            folder.mkdirs();

            for (SketchFile c : sketchFiles) {
                c.save();
            }
        } catch (Exception e) {
            Base.showWarning(Translate.t("Could not re-save sketch"),
                Translate.w("Could not properly re-save the sketch. You may be in trouble at this point, and it might be time to copy and paste your code to another text editor.", 40, "\n"), e);
        }
    }

    public boolean isReadOnly() {

        if (isInternal()) {
            return true;
        }

        File testFile = new File(folder, ".testWrite");
        boolean canWrite = false;
        try {
            testFile.createNewFile();
            if (testFile.exists()) {
                testFile.delete();
                canWrite = true;
            }
        } catch (Exception e) {
            return true;
        }

        if (!canWrite) {
            return true;
        }

        canWrite = true;

        for (SketchFile c : sketchFiles) {
            if (!c.file.canWrite()) {
                canWrite = false;
            }
        }
        return !canWrite;
    }

    public void redirectChannel(int c, PrintWriter pw) {
        if (c == 1) {
            stdoutRedirect = pw;
        }
        if (c == 2) {
            stderrRedirect = pw;
        }
    }

    public void unredirectChannel(int c) {
        if (c == 1) {
            if (stdoutRedirect != null) {
                stdoutRedirect.close();
                stdoutRedirect = null;
            }
        }
        if (c == 2) {
            if (stderrRedirect != null) {
                stderrRedirect.close();
                stderrRedirect = null;
            }
        }
    }

    public void message(String m) {
        message(m, 1);
    }

    public void message(String m, int chan) {
        if (m.trim() != "") {
            if (chan == 2) {
                if (stderrRedirect == null) {
                    Pattern p = Pattern.compile(editor.compiler.getErrorRegex());
                    Matcher match = p.matcher(m);
                    if (match.find()) {
                        String filename = match.group(1);
                        if (filename.startsWith(getBuildFolder().getAbsolutePath())) {
                            filename = filename.substring(getBuildFolder().getAbsolutePath().length() + 1);
                        }
                        editor.console.message("Error at line " + match.group(2) + " in file " + filename + ":\n    " + match.group(3) + "\n", true, false);
                        SketchFile f = getFileByName(filename);
                        if (f != null) {
                            int tn = editor.getTabByFile(f);
                            editor.selectTab(tn);
                            f.textArea.setCaretLineNumber(Integer.parseInt(match.group(2)));
                            f.textArea.addLineHighlight(Integer.parseInt(match.group(2)), Base.theme.getColor("editor.compile.error.bgcolor"));
                        }
                    } else {

                        p = Pattern.compile(editor.compiler.getWarningRegex());
                        match = p.matcher(m);
                        if (match.find()) {
                            String filename = match.group(1);
                            if (filename.startsWith(getBuildFolder().getAbsolutePath())) {
                                filename = filename.substring(getBuildFolder().getAbsolutePath().length() + 1);
                            }
                            editor.console.message("Warning at line " + match.group(2) + " in file " + filename + ":\n    " + match.group(3) + "\n", true, false);
                            SketchFile f = getFileByName(filename);
                            if (f != null) {
                                f.textArea.addLineHighlight(Integer.parseInt(match.group(2)), Base.theme.getColor("editor.compile.warning.bgcolor"));
                            }
                        } else {
                            editor.console.message(m, true, false);
                        }
                    }
                } else {
                    stderrRedirect.print(m);
                }
            } else {
                if (stdoutRedirect == null) {
                    editor.console.message(m, false, false);
                } else {
                    stdoutRedirect.print(m);
                }
            }
        }
    }

    public boolean compile() {
        ArrayList<String> includePaths;
        List<File> objectFiles = new ArrayList<File>();
        List<File> tobjs;
        HashMap<String, String> all = mergeAllProperties();

        for (SketchFile f : sketchFiles) {
            f.textArea.removeAllLineHighlights();
        }

        includePaths = getIncludePaths();

        settings.put("filename", name);
        settings.put("includes", preparePaths(includePaths));

        editor.statusNotice(Translate.t("Compiling Sketch..."));

        tobjs = compileSketch();
        if (tobjs == null) {
            editor.statusNotice(Translate.t("Error Compiling Sketch"));
            return false;
        }
        objectFiles.addAll(tobjs);
        setCompilingProgress(30);

        editor.statusNotice(Translate.t("Compiling Libraries..."));
        if (!compileLibraries()) {
            editor.statusNotice(Translate.t("Error Compiling Libraries"));
            return false;
        }

        setCompilingProgress(40);

        editor.statusNotice(Translate.t("Compiling Core..."));
        if (!compileCore(editor.core.getAPIFolder(), "core")) {
            editor.statusNotice(Translate.t("Error Compiling Core"));
            return false;
        }
        String coreLibs = "";
        setCompilingProgress(50);

        String liboption = all.get("compile.liboption");
        if (liboption == null) {
            liboption = "-l${library}";
        }

        if (parameters.get("extension") != null) {
            editor.statusNotice(Translate.t("Compiling Extension..."));
            File extension = new File(parameters.get("extension"));
            if (extension.exists() && extension.isDirectory()) {
                if (!compileCore(extension, extension.getName())) {
                    return false;
                }
                settings.put("library", extension.getName());
                coreLibs += "::" + parseString(liboption);
            }
        }

        settings.put("library", "core");
        coreLibs += "::" + parseString(liboption);

        editor.statusNotice(Translate.t("Linking Sketch..."));
        settings.put("filename", name);
        if (!compileLink(objectFiles, coreLibs)) {
            editor.statusNotice(Translate.t("Error Linking Sketch"));
            return false;
        }
        setCompilingProgress(60);

        editor.statusNotice(Translate.t("Extracting EEPROM..."));
        if (!compileEEP()) {
            editor.statusNotice(Translate.t("Error Extracting EEPROM"));
            return false;
        }
        setCompilingProgress(70);


        if (all.get("compile.lss") != null) {
            if (Base.preferences.getBoolean("compiler.generate_lss")) {
                editor.statusNotice(Translate.t("Generating Listing..."));
                File redirectTo = new File(buildFolder, name + ".lss");
                if (redirectTo.exists()) {
                    redirectTo.delete();
                }                

                boolean result = false;
                try {
                    redirectChannel(1, new PrintWriter(redirectTo));
                    result = compileLSS();
                    unredirectChannel(1);
                } catch (Exception e) {
                    result = false;
                }
                unredirectChannel(1);

                if (!result) {
                    editor.statusNotice(Translate.t("Error Generating Listing"));
                    return false;
                }
                if (Base.preferences.getBoolean("export.save_lss")) {
                    try {
                        Base.copyFile(new File(buildFolder, name + ".lss"), new File(folder, name + ".lss"));
                    } catch (Exception e) {
                        message("Error copying LSS file: " + e.getMessage() + "\n", 2);
                    }
                }
            }
        }

        setCompilingProgress(80);

        editor.statusNotice(Translate.t("Converting to HEX..."));
        if (!compileHEX()) {
            editor.statusNotice(Translate.t("Error Converting to HEX"));
            return false;
        }
        setCompilingProgress(90);

        if (Base.preferences.getBoolean("export.save_hex")) {
            try {
                Base.copyFile(new File(buildFolder, name + ".hex"), new File(folder, name + ".hex"));
            } catch (Exception e) {
                message("Error copying HEX file: " + e.getMessage() + "\n", 2);
            }
        }

        editor.statusNotice(Translate.t("Compiling Done"));
        return true;
    }

    public String parseString(String in)
    {
        int iStart;
        int iEnd;
        int iTest;
        String out;
        String start;
        String end;
        String mid;

        HashMap<String, String> tokens = mergeAllProperties();

        out = in;

        if (out == null) {
            return null;
        }

        iStart = out.indexOf("${");
        if (iStart == -1) {
            return out;
        }

        iEnd = out.indexOf("}", iStart);
        iTest = out.indexOf("${", iStart+1);
        while ((iTest > -1) && (iTest < iEnd)) {
            iStart = iTest;
            iTest = out.indexOf("${", iStart+1);
        }

        while (iStart != -1) {
            start = out.substring(0, iStart);
            end = out.substring(iEnd+1);
            mid = out.substring(iStart+2, iEnd);

            if (mid.equals("compiler.root")) {
                mid = editor.compiler.getFolder().getAbsolutePath();
            } else if (mid.equals("cache.root")) {
                mid = getCacheFolder().getAbsolutePath();
            } else if (mid.equals("core.root")) {
                mid = editor.core.getFolder().getAbsolutePath();
            } else if (mid.equals("board.root")) {
                mid = editor.board.getFolder().getAbsolutePath();
            } else if ((mid.length() > 5) && (mid.substring(0,5).equals("find:"))) {
                String f = mid.substring(5);

                File found;
                found = new File(editor.board.getFolder(), f);
                if (!found.exists()) {
                    found = new File(editor.core.getAPIFolder(), f);
                }
                if (!found.exists()) {
                    found = new File(editor.compiler.getFolder(), f);
                }
                if (!found.exists()) {
                    mid = f;
                } else {
                    mid = found.getAbsolutePath();
                }
            } else if (mid.equals("verbose")) {
                if (Base.preferences.getBoolean("export.verbose")) 
                    mid = tokens.get("upload." + editor.programmer + ".verbose");
                else 
                    mid = tokens.get("upload." + editor.programmer + ".quiet");
            } else if (mid.equals("port.base")) {
                if (Base.isWindows()) {
                    mid = editor.getSerialPort();
                } else {
                    String sp = editor.getSerialPort();
                    mid = sp.substring(sp.lastIndexOf('/') + 1);
                }
            } else if (mid.equals("port")) {
                if (Base.isWindows()) {
                    mid = "\\\\.\\" + editor.getSerialPort();
                } else {
                    mid = editor.getSerialPort();
                }
            } else {
                mid = tokens.get(mid);
            }

            if (mid != null) {
                out = start + mid + end;
            } else {
                out = start + end;
            }
            iStart = out.indexOf("${");
            iEnd = out.indexOf("}", iStart);
            iTest = out.indexOf("${", iStart+1);
            while ((iTest > -1) && (iTest < iEnd)) {
                iStart = iTest;
                iTest = out.indexOf("${", iStart+1);
            }
        }

        // This shouldn't be needed as the methodology should always find any tokens put in
        // by other token replacements.  But just in case, eh?
        if (out != in) {
            out = parseString(out);
        }

        return out;
    }

    private File compileFile(File src) {

        String fileName = src.getName();
        String recipe = null;

        HashMap<String, String> all = mergeAllProperties();

        if (fileName.endsWith(".cpp")) {
            recipe = all.get("compile.cpp");
        }
    
        if (fileName.endsWith(".c")) {
            recipe = all.get("compile.c");
        }
    
        if (fileName.endsWith(".S")) {
            recipe = all.get("compile.S");
        }

        if (recipe == null) {
            message("Error: I don't know how to compile " + fileName);
            return null;
        }

        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
        File dest = new File(buildFolder, baseName + ".o");

        if (dest.exists()) {
            if (dest.lastModified() > src.lastModified()) {
                return dest;
            }
        }

        settings.put("build.path", buildFolder.getAbsolutePath());
        settings.put("source.name", src.getAbsolutePath());
        settings.put("object.name", dest.getAbsolutePath());

        String compiledString = parseString(recipe);

        if (!execAsynchronously(compiledString)) {
            return null;
        }
        if (!dest.exists()) {
            return null;
        }

        return dest;
    }

    public File getCacheFolder() {
        File cacheRoot = Base.getUserCacheFolder();
        File coreCache = new File(cacheRoot, editor.core.getName());
        File boardCache = new File(coreCache, editor.board.getName());
        if (!boardCache.exists()) {  
            boardCache.mkdirs();
        }
        return boardCache;
    }

    public File getCacheFile(String fileName) {
        File cacheFolder = getCacheFolder();
        File out = new File(cacheFolder, fileName);
        return out;
    }

    public boolean compileCore(File core, String name) {
        File archive = getCacheFile("lib" + name + ".a");

        HashMap<String, String> all = mergeAllProperties();
        String recipe = all.get("compile.ar");

        settings.put("library", archive.getAbsolutePath());

        long archiveDate = 0;
        if (archive.exists()) {
            archiveDate = archive.lastModified();
        }

        ArrayList<File> fileList = new ArrayList<File>();

        fileList.addAll(findFilesInFolder(core, "S", true));
        fileList.addAll(findFilesInFolder(core, "c", true));
        fileList.addAll(findFilesInFolder(core, "cpp", true));

        String boardFiles = all.get("build.files");
        if (boardFiles != null) {
            String[] bfl = boardFiles.split("::");
            for (String bf : bfl) {
                File f = new File(editor.board.getFolder(), bf);
                if (f.exists()) {
                    if (!f.isDirectory()) {
                        if (f.getName().endsWith(".S") || f.getName().endsWith(".c") || f.getName().endsWith(".cpp")) {
                            fileList.add(f);
                        }
                    }
                }
            }
        }

        for (File f : fileList) {
            if (f.lastModified() > archiveDate) {
                File out = compileFile(f);
                if (out == null) {
                    return false;
                }
                settings.put("object.name", out.getAbsolutePath());
                String command = parseString(recipe);
                boolean ok = execAsynchronously(command);
                if (!ok) {
                    return false;
                }
                out.delete();
            }
        }
        return true;
    }

    public boolean compileLibrary(Library lib) {
        File archive = getCacheFile("lib" + lib.getName() + ".a");
        File utility = lib.getUtilityFolder();
        HashMap<String, String> all = mergeAllProperties();

        String recipe = all.get("compile.ar");

        settings.put("library", archive.getAbsolutePath());

        long archiveDate = 0;
        if (archive.exists()) {
            archiveDate = archive.lastModified();
        }

        ArrayList<File> fileList = lib.getSourceFiles();

        String origIncs = settings.get("includes");
        settings.put("includes", origIncs + "::" + "-I" + utility.getAbsolutePath());

        for (File f : fileList) {
            if (f.lastModified() > archiveDate) {
                File out = compileFile(f);
                if (out == null) {
                    return false;
                }
                settings.put("object.name", out.getAbsolutePath());
                String command = parseString(recipe);
                boolean ok = execAsynchronously(command);
                if (!ok) {
                    return false;
                }
                out.delete();
            }
        }
        settings.put("includes", origIncs);
        return true;
    }

    private List<File> compileFiles(File dest, List<File> sSources, List<File> cSources, List<File> cppSources) {

        List<File> objectPaths = new ArrayList<File>();
        HashMap<String, String> all = mergeAllProperties();

        settings.put("build.path", dest.getAbsolutePath());

        for (File file : sSources) {
            File objectFile = new File(dest, file.getName() + ".o");
            objectPaths.add(objectFile);

            settings.put("source.name", file.getAbsolutePath());
            settings.put("object.name", objectFile.getAbsolutePath());

            if (objectFile.exists() && objectFile.lastModified() > file.lastModified()) {
                if (Base.preferences.getBoolean("compiler.verbose")) {
                    message("Skipping " + file.getAbsolutePath() + " as not modified.\n", 1);
                }
                continue;
            }

            if(!execAsynchronously(parseString(all.get("compile.S"))))
                return null;
            if (!objectFile.exists()) 
                return null;
        }

        for (File file : cSources) {
            File objectFile = new File(dest, file.getName() + ".o");
            objectPaths.add(objectFile);

            settings.put("source.name", file.getAbsolutePath());
            settings.put("object.name", objectFile.getAbsolutePath());

            if (objectFile.exists() && objectFile.lastModified() > file.lastModified()) {
                if (Base.preferences.getBoolean("compiler.verbose")) {
                    message("Skipping " + file.getAbsolutePath() + " as not modified.\n", 1);
                }
                continue;
            }

            if(!execAsynchronously(parseString(all.get("compile.c"))))
                return null;
            if (!objectFile.exists()) 
                return null;
        }

        for (File file : cppSources) {
            File objectFile = new File(dest, file.getName() + ".o");
            objectPaths.add(objectFile);

            settings.put("source.name", file.getAbsolutePath());
            settings.put("object.name", objectFile.getAbsolutePath());

            if (objectFile.exists() && objectFile.lastModified() > file.lastModified()) {
                if (Base.preferences.getBoolean("compiler.verbose")) {
                    message("Skipping " + file.getAbsolutePath() + " as not modified.\n", 1);
                }
                continue;
            }

            if(!execAsynchronously(parseString(all.get("compile.cpp"))))
                return null;
            if (!objectFile.exists()) 
                return null;
        }

        return objectPaths;
    }

    private String preparePaths(ArrayList<String> includePaths) {
        String includes = "";
        if (parameters.get("extension") != null) {
            includes = includes + "-I" + parameters.get("extension") + "::";
        }
        for (int i = 0; i < includePaths.size(); i++)
        {
            includes = includes + ("-I" + (String) includePaths.get(i)) + "::";
        }
        return includes;
    }

    private List<File> compileSketch() {
        return compileFiles(
                buildFolder,
                findFilesInFolder(buildFolder, "S", false),
                findFilesInFolder(buildFolder, "c", false),
                findFilesInFolder(buildFolder, "cpp", false));
    } 

    static public ArrayList<File> findFilesInFolder(File folder,
            String extension, boolean recurse) {
        ArrayList<File> files = new ArrayList<File>();

        if (folder.listFiles() == null)
            return files;

        for (File file : folder.listFiles()) {
            if (file.getName().startsWith("."))
                continue; // skip hidden files

            if (file.getName().endsWith("." + extension))
                files.add(file);

            if (recurse && file.isDirectory()) {
                files.addAll(findFilesInFolder(file, extension, true));
            }
        }

        return files;
    }

    static private boolean createFolder(File folder) {
        if (folder.isDirectory())
            return false;
        if (!folder.mkdir())
            return false;
        return true;
    }

    private boolean compileLibraries () {
        for (Library lib : getImportedLibraries()) {
            if (!compileLibrary(lib)) {
                return false;
            }
        }
        return true;
    }

    private boolean compileLink(List<File> objectFiles, String coreLibs) {
        HashMap<String, String> all = mergeAllProperties();
        String baseCommandString = all.get("compile.link");
        String commandString = "";
        String objectFileList = "";

        settings.put("libraries.path", getCacheFolder().getAbsolutePath());

        String neverInclude = all.get("neverinclude");
        if (neverInclude == null) {
            neverInclude = "";
        }
        neverInclude.replaceAll(" ", "::");
        String neverIncludes[] = neverInclude.split("::");

        String liboption = all.get("compile.liboption");
        if (liboption == null) {
            liboption = "-l${library}";
        }

        String liblist = "";
        for (Library lib : getImportedLibraries()) {
            File aFile = getCacheFile("lib" + lib.getName() + ".a");
            String headerName = lib.getName() + ".h";
            boolean inc = true;
            for (String ni : neverIncludes) {
                if (ni.equals(headerName)) {
                    inc = false;
                }
            }

            if (aFile.exists() && inc) {
                settings.put("library", lib.getName());
                liblist += "::" + parseString(liboption);
            }
        }
        liblist += "";

        liblist += coreLibs;

        settings.put("libraries", liblist);

        for (File file : objectFiles) {
            objectFileList = objectFileList + file.getAbsolutePath() + "::";
        }

        settings.put("build.path", buildFolder.getAbsolutePath());
        settings.put("object.filelist", objectFileList);

        commandString = parseString(baseCommandString);
        return execAsynchronously(commandString);
    }

    private boolean compileEEP() {
        HashMap<String, String> all = mergeAllProperties();
        return execAsynchronously(parseString(all.get("compile.eep")));
    }

    private boolean compileLSS() {
        HashMap<String, String> all = mergeAllProperties();
        return execAsynchronously(parseString(all.get("compile.lss")));
    }

    private boolean compileHEX() {
        HashMap<String, String> all = mergeAllProperties();
        System.err.println(all.get("compile.hex"));
        return execAsynchronously(parseString(all.get("compile.hex")));
    }

    public boolean execAsynchronously(String command) {
        HashMap<String, String> all = mergeAllProperties();
        if (command == null) {
            return true;
        }

        String[] commandArray = command.split("::");
        List<String> stringList = new ArrayList<String>();
        for(String string : commandArray) {
            string = string.trim();
            if(string != null && string.length() > 0) {
                stringList.add(string);
            }
        }

        stringList.set(0, stringList.get(0).replace("//", "/"));

        ProcessBuilder process = new ProcessBuilder(stringList);
//        process.redirectOutput(ProcessBuilder.Redirect.PIPE);
//        process.redirectError(ProcessBuilder.Redirect.PIPE);
        if (buildFolder != null) {
            process.directory(buildFolder);
        }
        Map<String, String> environment = process.environment();

        String pathvar = "PATH";
        if (Base.isWindows()) {
            pathvar = "Path";
        }

        String paths = all.get("path");
        if (paths != null) {
            for (String p : paths.split("::")) {
                String oPath = environment.get(pathvar);
                if (oPath == null) {
                    oPath = System.getenv(pathvar);
                }
                environment.put(pathvar, oPath + File.pathSeparator + parseString(p));
            }
        }

        String env = all.get("environment");
        if (env != null) {
            for (String ev : env.split("::")) {
                String[] bits = ev.split("=");
                if (bits.length == 2) {
                    environment.put(bits[0], parseString(bits[1]));
                }
            }
        }

        if (Base.preferences.getBoolean("compiler.verbose")) {
            for (String component : stringList) {
                message(component + " ", 1);
            }
            message("\n", 1);
        }

        Process proc;
        try {
            proc = process.start();
        } catch (Exception e) {
            message(e.toString(), 2);
            return false;
        }

        Base.processes.add(proc);

        MessageSiphon in = new MessageSiphon(proc.getInputStream(), this);
        MessageSiphon err = new MessageSiphon(proc.getErrorStream(), this);
        in.setChannel(1);
        err.setChannel(2);
        boolean running = true;
        int result = -1;
        while (running) {
            try {
                if (in.thread != null)
                    in.thread.join();
                if (err.thread != null)
                    err.thread.join();
                result = proc.waitFor();
                running = false;
            } catch (Exception ignored) { }
        }
        Base.processes.remove(proc);
        if (result == 0) {
            return true;
        }
        return false;
    }

    public boolean isUntitled() {
        if (folder.getParentFile().equals(Base.getTmpDir())) {
            if (name.startsWith("untitled")) {
                return true;
            }
        }
        return false;
    }


    // Scan all the "common" areas where examples may be found, and see if the 
    // start of the sketch folder's path matches any of them.

    public boolean isInternal() {
        String path = folder.getAbsolutePath();
        String basePath = Base.getContentFile(".").getAbsolutePath() + File.separator;
        String libsPath = Base.getSketchbookLibrariesPath() + File.separator;
        String cachePath = getCacheFolder().getAbsolutePath() + File.separator;
        String corePath = editor.core.getFolder().getAbsolutePath() + File.separator;
        String boardPath = editor.board.getFolder().getAbsolutePath() + File.separator;

        if (path.startsWith(basePath)) return true;
        if (path.startsWith(libsPath)) return true;
        if (path.startsWith(cachePath)) return true;
        if (path.startsWith(corePath)) return true;
        if (path.startsWith(boardPath)) return true;

        return false;    
    }

    public boolean validSourceFile(File f) {
        return validSourceFile(f.getName());
    }

    public boolean validSourceFile(String f) {
        if (f.endsWith(".ino")) return true;
        if (f.endsWith(".pde")) return true;
        if (f.endsWith(".cpp")) return true;
        if (f.endsWith(".c")) return true;
        if (f.endsWith(".h")) return true;
        if (f.endsWith(".hh")) return true;
        if (f.endsWith(".S")) return true;
        return false;
    }

    public void cleanBuild() {
        System.gc();
        Base.removeDescendants(buildFolder);
    }

    // Merge all the layers into one single property file
    // class.  This will be the compiler, then the core, then
    // the board, then the pragmas and finally the run-time settings.

    public HashMap<String, String> mergeAllProperties() {
        HashMap<String, String> total = new HashMap<String, String>();

        if (editor.compiler == null) {
            System.err.println("==> No compiler");
        } else if (editor.compiler.getProperties() == null) {
            System.err.println("==> No compiler properties");
        } else {
            total.putAll(editor.compiler.getProperties().toHashMap(true));
        }

        if (editor.core == null) { 
            Base.error("==> No core");
        } else if (editor.core.getProperties() == null) {
            Base.error("==> No core properties");
        } else {
            total.putAll(editor.core.getProperties().toHashMap(true));
        }

        if (editor.board == null) {
            Base.error("==> No board");
        } else if (editor.board.getProperties() == null) {
            Base.error("==> No board properties");
        } else {
            total.putAll(editor.board.getProperties().toHashMap(true));
        }

        if (parameters != null) {
            total.putAll(parameters);
        }
        if (settings != null) {
            total.putAll(settings);
        }

        return total;
    }

    public void about() {
        editor.message("Sketch folder: " + folder.getAbsolutePath() + "\n", 0);
        editor.message("Selected board: " + editor.board.getName() + "\n", 0);
        editor.message("Board folder: " + editor.board.getFolder().getAbsolutePath() + "\n", 0);
    }

    public void handleNewFile() {
        String filename = JOptionPane.showInputDialog("Enter Filename");
        if (filename == null) {
            return;
        }
        File newFile = new File(folder, filename);
        if (!validSourceFile(newFile)) {
            JOptionPane.showMessageDialog(null, "File is not a valid source file", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (newFile.exists()) {
            JOptionPane.showMessageDialog(null, "File already exists", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            newFile.createNewFile();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Error creating file: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!newFile.exists()) {
            JOptionPane.showMessageDialog(null, "Error creating file", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        loadFile(newFile);
    }

    public void handleRenameTab() {
        SketchEditor activeTab = editor.getActiveTab();
        File oldFile = activeTab.getFile();
        if (oldFile.equals(getMainFile().getFile())) {
            JOptionPane.showMessageDialog(null, "You cannot rename the main sketch file.  Use 'File -> Save As' instead.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String oldName = oldFile.getName();
        String newName = JOptionPane.showInputDialog("Enter New Filename", oldName);
        if (newName == null) {
            return;
        }
        File newFile = new File(folder, newName);
        if (!validSourceFile(newFile)) {
            JOptionPane.showMessageDialog(null, "File is not a valid source file", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (newFile.exists()) {
            JOptionPane.showMessageDialog(null, "File already exists", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        SketchFile sf = getFileByName(oldName);
        try {
            oldFile.renameTo(newFile);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Error renaming file: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
            
        if (!newFile.exists()) {
            JOptionPane.showMessageDialog(null, "Error renaming file", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        sf.setFile(newFile);
    }

    public void programBootloader(String programmer) {
        String uploadCommand;

        File bootloader = editor.board.getBootloader();
        String blName = bootloader.getAbsolutePath();

        HashMap<String, String> all = mergeAllProperties();
        editor.statusNotice(Translate.t("Burning Bootloader..."));
        settings.put("filename", blName);
        settings.put("filename.elf", blName + ".elf");
        settings.put("filename.hex", blName + ".hex");
        settings.put("filename.eep", blName + ".eep");

        boolean isJava = true;
        uploadCommand = editor.board.get("upload." + programmer + ".command.java");
        if (uploadCommand == null) {
            uploadCommand = editor.core.get("upload." + programmer + ".command.java");
        }
        if (uploadCommand == null) {
            isJava = false;
            uploadCommand = editor.board.get("upload." + programmer + ".command." + Base.getOSFullName());
        }
        if (uploadCommand == null) {
            uploadCommand = editor.board.get("upload." + programmer + ".command." + Base.getOSName());
        }
        if (uploadCommand == null) {
            uploadCommand = editor.board.get("upload." + programmer + ".command");
        }
        if (uploadCommand == null) {
            uploadCommand = editor.core.get("upload." + programmer + ".command." + Base.getOSFullName());
        }
        if (uploadCommand == null) {
            uploadCommand = editor.core.get("upload." + programmer + ".command." + Base.getOSName());
        }
        if (uploadCommand == null) {
            uploadCommand = editor.core.get("upload." + programmer + ".command");
        }

        if (uploadCommand == null) {
            message("No upload command defined for board\n", 2);
            editor.statusNotice(Translate.t("Upload Failed"));
            return;
        }

   
        if (isJava) {
            Plugin uploader;
            uploader = Base.plugins.get(uploadCommand);
            if (uploader == null) {
                message("Upload class " + uploadCommand + " not found.\n", 2);
                editor.statusNotice(Translate.t("Upload Failed"));
                return;
            }
            try {
                if ((uploader.flags() & BasePlugin.LOADER) == 0) {
                    message(uploadCommand + "is not a valid loader plugin.\n", 2);
                    editor.statusNotice(Translate.t("Upload Failed"));
                    return;
                }
                uploader.run();
            } catch (Exception e) {
                editor.statusNotice(Translate.t("Upload Failed"));
                message(e.toString(), 2);
                return;
            }
            editor.statusNotice(Translate.t("Upload Complete"));
            return;
        }

        String[] spl;
        spl = parseString(uploadCommand).split("::");

        String executable = spl[0];
        if (Base.isWindows()) {
            executable = executable + ".exe";
        }

        File exeFile = new File(folder, executable);
        File tools;
        if (!exeFile.exists()) {
            tools = new File(folder, "tools");
            exeFile = new File(tools, executable);
        }
        if (!exeFile.exists()) {
            exeFile = new File(editor.core.getFolder(), executable);
        }
        if (!exeFile.exists()) {
            tools = new File(editor.core.getFolder(), "tools");
            exeFile = new File(tools, executable);
        }
        if (!exeFile.exists()) {
            exeFile = new File(executable);
        }
        if (exeFile.exists()) {
            executable = exeFile.getAbsolutePath();
        }

        spl[0] = executable;

        // Parse each word, doing String replacement as needed, trimming it, and
        // generally getting it ready for executing.

        String commandString = executable;
        for (int i = 1; i < spl.length; i++) {
            String tmp = spl[i];
            tmp = tmp.trim();
            if (tmp.length() > 0) {
                commandString += "::" + tmp;
            }
        }

        boolean dtr = false;
        boolean rts = false;


        String ulu = all.get("upload." + programmer + ".using");
        if (ulu == null) ulu = "serial";

        String doDtr = all.get("upload." + programmer + ".dtr");
        if (doDtr != null) {
            if (doDtr.equals("yes")) {
                dtr = true;
            }
        }
        String doRts = all.get("upload." + programmer + ".rts");
        if (doRts != null) {
            if (doRts.equals("yes")) {
                rts = true;
            }
        }

        if (ulu.equals("serial") || ulu.equals("usbcdc"))
        {
            editor.grabSerialPort();
            if (dtr || rts) {
                assertDTRRTS(dtr, rts);
            }
        }
        if (ulu.equals("usbcdc")) {
            try {
                String baud = all.get("upload." + programmer + ".reset.baud");
                if (baud != null) {
                    System.err.println("Opening serial port at " + baud + " baud");
                    int b = Integer.parseInt(baud);
          //          editor.grabSerialPort();
                    
                    SerialPort serialPort = Serial.requestPort(editor.getSerialPort(), this, b);
                    if (serialPort == null) {
                        Base.error("Unable to lock serial port");
                        return;
                    }
                    Thread.sleep(1000);
                    Serial.releasePort(serialPort);
                    serialPort = null;
                    System.gc();
                    Thread.sleep(1500);
                }
            } catch (Exception e) {
                Base.error(e);
            }
        }

        boolean res = execAsynchronously(commandString);
        if (ulu.equals("serial") || ulu.equals("usbcdc"))
        {
            if (dtr || rts) {
                assertDTRRTS(false, false);
            }
            editor.releaseSerialPort();
        }
        if (res) {
            editor.statusNotice(Translate.t("Upload Complete"));
        } else {
            editor.statusNotice(Translate.t("Upload Failed"));
        }
    }
}

