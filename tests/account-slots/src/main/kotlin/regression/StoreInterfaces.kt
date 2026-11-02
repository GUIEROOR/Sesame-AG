package io.github.aoguai.sesameag

import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor

interface IStoreListener {
    fun onChanged(collection: Int, owner: String, name: String)
    abstract class Stub : Binder(), IStoreListener
}

interface IStoreTransaction {
    fun stat(collection: Int, owner: String, name: String): Bundle
    fun list(collection: Int, owner: String, name: String): Array<String>
    fun ensureDirectory(collection: Int, owner: String, name: String): Boolean
    fun read(collection: Int, owner: String, name: String): ParcelFileDescriptor?
    fun write(collection: Int, owner: String, name: String, source: ParcelFileDescriptor)
    fun append(collection: Int, owner: String, name: String, source: ParcelFileDescriptor)
    fun truncate(collection: Int, owner: String, name: String, length: Long)
    fun remove(collection: Int, owner: String, name: String): Boolean
    fun close()
    abstract class Stub : Binder(), IStoreTransaction
}
