using System.IO;
using System.Threading;
using System;

public class FrameStream : Stream
{
    public override bool CanRead => false;
    public override bool CanSeek => false;
    public override bool CanWrite => true;
    public override long Length => throw new NotSupportedException();
    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

    readonly byte[][] frameBuffers;
    int currentFrame = 0;
    int currentWritePositionInFrame = 0;
    bool newFrameAvailable;

    public bool IsNewFrameAvailable => newFrameAvailable;

    readonly SemaphoreSlim semaphore = new(1);

    private FrameStream()
    {

    }

    public FrameStream(int frameByteSize)
    {
        frameBuffers = new byte[][] { new byte[frameByteSize], new byte[frameByteSize] };
    }

    public override void Flush()
    {

    }

    public override int Read(byte[] buffer, int offset, int count)
    {
        throw new NotSupportedException();
    }

    public override void Write(byte[] buffer, int offset, int count)
    {
        semaphore.Wait();

        int bufferIndex = 0;
        while (count > 0)
        {
            int freeBytesLeftInFrame = frameBuffers[currentFrame % 2].Length - currentWritePositionInFrame;
            int writeLength = Math.Min(count, freeBytesLeftInFrame);

            Array.Copy(buffer, bufferIndex, frameBuffers[currentFrame % 2], currentWritePositionInFrame, writeLength);
            currentWritePositionInFrame += writeLength;
            bufferIndex += writeLength;
            count -= writeLength;

            if (currentWritePositionInFrame == frameBuffers[currentFrame % 2].Length)
            {
                currentFrame++;
                currentWritePositionInFrame = 0;
                newFrameAvailable = true;
            }
        }

        semaphore.Release();
    }

    public FrameAccessor GetFrameAccessor()
    {
        newFrameAvailable = false;
        return new FrameAccessor(semaphore, frameBuffers[(currentFrame + 1) % 2]);
    }

    public class FrameAccessor : IDisposable
    {
        readonly SemaphoreSlim semaphore;
        readonly public byte[] frame;

        public FrameAccessor(SemaphoreSlim semaphore, byte[] frame)
        {
            this.semaphore = semaphore;
            semaphore.Wait();

            this.frame = frame;
        }

        public void Dispose() => semaphore.Release();
    }

    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
}