package com.lanftp;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;

final class SafFileSystem implements FtpServer.Vfs {
    private final ContentResolver resolver;
    private final Uri tree;
    private final String rootId;
    private static final String[] COLUMNS={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
    SafFileSystem(ContentResolver r,Uri t){resolver=r;tree=t;rootId=DocumentsContract.getTreeDocumentId(t);}
    private Uri doc(String id){return DocumentsContract.buildDocumentUriUsingTree(tree,id);}
    private static FtpServer.Entry entry(Cursor c){return new FtpServer.Entry(c.getString(1),DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2)),c.isNull(3)?0:c.getLong(3),c.isNull(4)?0:c.getLong(4));}
    private String resolve(String path)throws IOException {
        if(!FtpServer.normalize("/",path).equals(path))throw new IOException("Invalid path");
        String id=rootId;
        for(String part:path.substring(1).split("/")){
            if(part.isEmpty())continue;String found=null;
            Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,id);
            try(Cursor c=resolver.query(children,COLUMNS,null,null,null)){
                if(c==null)throw new IOException("Cannot read folder");
                while(c.moveToNext())if(part.equals(c.getString(1))){found=c.getString(0);break;}
            }catch(RuntimeException e){throw new IOException("Folder unavailable",e);}
            if(found==null)throw new FileNotFoundException(path);id=found;
        }return id;
    }
    public FtpServer.Entry stat(String path)throws IOException {
        try(Cursor c=resolver.query(doc(resolve(path)),COLUMNS,null,null,null)){
            if(c==null||!c.moveToFirst())throw new FileNotFoundException(path);return entry(c);
        }catch(RuntimeException e){throw new IOException("File unavailable",e);}
    }
    public List<FtpServer.Entry> list(String path)throws IOException {
        List<FtpServer.Entry> result=new ArrayList<>();
        try(Cursor c=resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,resolve(path)),COLUMNS,null,null,null)){
            if(c==null)throw new IOException("Cannot list folder");while(c.moveToNext())result.add(entry(c));
        }catch(RuntimeException e){throw new IOException("Folder unavailable",e);}
        result.sort((a,b)->a.directory==b.directory?a.name.compareToIgnoreCase(b.name):(a.directory?-1:1));return result;
    }
    public InputStream open(String path)throws IOException {
        InputStream in=resolver.openInputStream(doc(resolve(path)));if(in==null)throw new FileNotFoundException(path);return in;
    }
}
