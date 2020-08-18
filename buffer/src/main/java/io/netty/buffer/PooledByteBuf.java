/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

import io.netty.util.internal.ObjectPool.Handle;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;

/**
 * 对象池化的 ByteBuf 抽象基类，为基于对象池的 ByteBuf 实现类，提供公用的方法
 * @param <T>
 */
abstract class PooledByteBuf<T> extends AbstractReferenceCountedByteBuf {

    private final Handle<PooledByteBuf<T>> recyclerHandle;//Recycler 处理器，用于回收对象

    protected PoolChunk<T> chunk;//Chunk 对象
    protected long handle;//从 Chunk 对象中分配的内存块所处的位置
    protected T memory;//内存空间。具体什么样的数据，通过子类设置泛型。
    protected int offset;//{@link #memory} 开始位置
    protected int length;//容量
    int maxLength;//占用 {@link #memory} 的大小
    PoolThreadCache cache;
    ByteBuffer tmpNioBuf;//临时 ByteBuff 对象
    private ByteBufAllocator allocator;//ByteBuf 分配器对象

    @SuppressWarnings("unchecked")
    protected PooledByteBuf(Handle<? extends PooledByteBuf<T>> recyclerHandle, int maxCapacity) {
        super(maxCapacity);
        this.recyclerHandle = (Handle<PooledByteBuf<T>>) recyclerHandle;
    }

    //一般是基于 pooled 的 PoolChunk 对象，初始化 PooledByteBuf 对象
    void init(PoolChunk<T> chunk, ByteBuffer nioBuffer,
              long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        init0(chunk, nioBuffer, handle, offset, length, maxLength, cache);
    }

    //基于 unPoolooled 的 PoolChunk 对象，初始化 PooledByteBuf 对象
    void initUnpooled(PoolChunk<T> chunk, int length) {
        init0(chunk, null, 0, chunk.offset, length, length, null);
    }

    private void init0(PoolChunk<T> chunk, ByteBuffer nioBuffer,
                       long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        assert handle >= 0;
        assert chunk != null;

        this.chunk = chunk;
        memory = chunk.memory;
        tmpNioBuf = nioBuffer;
        allocator = chunk.arena.parent;
        this.cache = cache;
        this.handle = handle;
        this.offset = offset;
        this.length = length;
        this.maxLength = maxLength;
    }

    /**
     * Method must be called before reuse this {@link PooledByteBufAllocator}
     * 每次在重用 PooledByteBuf 对象时，需要调用该方法，重置属性
     */
    final void reuse(int maxCapacity) {
        maxCapacity(maxCapacity);// 设置最大容量
        resetRefCnt();// 设置引用数量为 0
        setIndex0(0, 0);// 重置读写索引为 0
        discardMarks();// 重置读写标记位为 0
    }

    @Override
    public final int capacity() {
        return length;
    }

    @Override
    public int maxFastWritableBytes() {
        return Math.min(maxLength, maxCapacity()) - writerIndex;
    }

    //调整容量大小。在这个过程中，根据情况，可能对 memory 扩容或缩容
    @Override
    public final ByteBuf capacity(int newCapacity) {
        if (newCapacity == length) {
            ensureAccessible();
            return this;
        }
        checkNewCapacity(newCapacity);// 校验新的容量，不能超过最大容量
        if (!chunk.unpooled) {
            // Chunk 内存，是池化
            // If the request capacity does not require reallocation, just update the length of the memory.
            // 扩容
            if (newCapacity > length) {
                if (newCapacity <= maxLength) {
                    length = newCapacity;
                    return this;
                }
            } else if (newCapacity > maxLength >>> 1 &&
                    (maxLength > 512 || newCapacity > maxLength - 16)) {
                // 缩容
                // here newCapacity < length
                length = newCapacity;
                trimIndicesToCapacity(newCapacity);
                return this;
            }
        }

        // Reallocation required.
        // 重新分配新的内存空间，并将数据复制到其中。并且，释放老的内存空间。
        chunk.arena.reallocate(this, newCapacity, true);
        return this;
    }

    @Override
    public final ByteBufAllocator alloc() {
        return allocator;
    }

    @Override
    public final ByteOrder order() {
        return ByteOrder.BIG_ENDIAN;
    }

    @Override
    public final ByteBuf unwrap() {
        return null;
    }

    //创建池化的 PooledDuplicatedByteBuf.newInstance 对象
    @Override
    public final ByteBuf retainedDuplicate() {
        return PooledDuplicatedByteBuf.newInstance(this, this, readerIndex(), writerIndex());
    }

    @Override
    public final ByteBuf retainedSlice() {
        final int index = readerIndex();
        return retainedSlice(index, writerIndex() - index);
    }

    @Override
    public final ByteBuf retainedSlice(int index, int length) {
        return PooledSlicedByteBuf.newInstance(this, this, index, length);
    }

    //获得临时 ByteBuf 对象( tmpNioBuf )
    protected final ByteBuffer internalNioBuffer() {
        ByteBuffer tmpNioBuf = this.tmpNioBuf;
        // 为空，创建临时 ByteBuf 对象
        if (tmpNioBuf == null) {
            this.tmpNioBuf = tmpNioBuf = newInternalNioBuffer(memory);
        } else {
            tmpNioBuf.clear();
        }
        return tmpNioBuf;
    }

    protected abstract ByteBuffer newInternalNioBuffer(T memory);

    //当引用计数为 0 时，调用该方法，进行内存回收
    @Override
    protected final void deallocate() {
        if (handle >= 0) {
            final long handle = this.handle;// 重置属性
            this.handle = -1;
            memory = null;
            chunk.arena.free(chunk, tmpNioBuf, handle, maxLength, cache);// 释放内存回 Arena 中
            tmpNioBuf = null;
            chunk = null;
            recycle();// 回收对象
        }
    }

    private void recycle() {
        recyclerHandle.recycle(this);
    }

    //获得指定位置在 memory 变量中的位置
    protected final int idx(int index) {
        return offset + index;
    }

    final ByteBuffer _internalNioBuffer(int index, int length, boolean duplicate) {
        index = idx(index);// memory 中的开始位置
        // duplicate 复制一个 ByteBuffer 对象，共享数据
        // position + limit 设置位置和大小限制
        ByteBuffer buffer = duplicate ? newInternalNioBuffer(memory) : internalNioBuffer();
        buffer.limit(index + length).position(index);
        return buffer;
    }

    ByteBuffer duplicateInternalNioBuffer(int index, int length) {
        checkIndex(index, length);
        return _internalNioBuffer(index, length, true);// 获得临时 ByteBuf 对象
    }

    @Override
    public final ByteBuffer internalNioBuffer(int index, int length) {
        checkIndex(index, length);
        return _internalNioBuffer(index, length, false);
    }

    @Override
    public final int nioBufferCount() {
        return 1;
    }

    //返回 ByteBuf 指定范围包含的 ByteBuffer 对象( 共享 )
    @Override
    public final ByteBuffer nioBuffer(int index, int length) {
        // slice 创建 [index, index+length] 子缓冲区，共享数据
        return duplicateInternalNioBuffer(index, length).slice();
    }

    //返回 ByteBuf 指定范围内包含的 ByteBuffer 数组( 共享 )。
    @Override
    public final ByteBuffer[] nioBuffers(int index, int length) {
        return new ByteBuffer[] { nioBuffer(index, length) };
    }

    @Override
    public final boolean isContiguous() {
        return true;
    }

    @Override
    public final int getBytes(int index, GatheringByteChannel out, int length) throws IOException {
        return out.write(duplicateInternalNioBuffer(index, length));// 写入到 FileChannel 中
    }

    @Override
    public final int readBytes(GatheringByteChannel out, int length) throws IOException {
        checkReadableBytes(length);
        int readBytes = out.write(_internalNioBuffer(readerIndex, length, false));
        readerIndex += readBytes;
        return readBytes;
    }

    @Override
    public final int getBytes(int index, FileChannel out, long position, int length) throws IOException {
        return out.write(duplicateInternalNioBuffer(index, length), position);
    }

    @Override
    public final int readBytes(FileChannel out, long position, int length) throws IOException {
        checkReadableBytes(length);
        int readBytes = out.write(_internalNioBuffer(readerIndex, length, false), position);
        readerIndex += readBytes;
        return readBytes;
    }

    @Override
    public final int setBytes(int index, ScatteringByteChannel in, int length) throws IOException {
        try {
            // 获得临时 ByteBuf 对象
            // 写入临时 ByteBuf 对象
            return in.read(internalNioBuffer(index, length));
        } catch (ClosedChannelException ignored) {
            return -1;
        }
    }

    @Override
    public final int setBytes(int index, FileChannel in, long position, int length) throws IOException {
        try {
            return in.read(internalNioBuffer(index, length), position);
        } catch (ClosedChannelException ignored) {
            return -1;
        }
    }
}
