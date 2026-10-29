package com.jw.maps.core;
import java.io.*;
import java.nio.file.*;
/** The active file contains its revision. Atomic rename commits both geometry and revision. */
public final class PackStore {
    public final File file;
    public PackStore(File f) { file=f; }
    public synchronized void activate(byte[] b,long expectedVersion) throws IOException {
        MapPack p=MapPack.parse(b);
        if(p.version!=expectedVersion) throw new IOException("Map revision differs from signed release");
        if(file.exists()&&p.version<=load().version) throw new IOException("Map rollback/replay rejected");
        File part=new File(file+".part");
        try {
            try(FileOutputStream out=new FileOutputStream(part)) { out.write(b); out.getFD().sync(); }
            Files.move(part.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { part.delete(); }
    }
    public synchronized MapPack load() throws IOException { try(InputStream in=new FileInputStream(file)) { return MapPack.parse(Artifacts.bounded(in,(int)UpdateManifest.DATA_LIMIT)); } }
}
