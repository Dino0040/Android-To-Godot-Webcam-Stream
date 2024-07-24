using FFMpegCore.Pipes;
using FFMpegCore;
using Godot;
using System;
using System.Net.Sockets;
using System.Threading.Tasks;

public partial class Camera : Control
{
	TcpListener listener;
	TcpClient client;
	NetworkStream networkStream;
	FrameStream frameStream;
    Rid textureRid;
	Texture2D texture2D;

    RenderingDevice renderingDevice;

    [Export] private PackedScene cameraView;

	public override void _Ready()
	{
		_ = ReceiveVideoAsync().ContinueWith(x =>
		{
			if(x.Exception != null)
			{
				GD.Print(x.Exception);
				throw (x.Exception.InnerException);
			}
		});
	}

	private async Task ReceiveVideoAsync()
	{
		listener = new TcpListener(System.Net.IPAddress.Any, 10040);
		listener.Start();
		GD.Print("Waiting for connection...");

		while (true)
		{
			client = await listener.AcceptTcpClientAsync();
			networkStream = client.GetStream();

			byte[] infoMessage = new byte[12];
			int infoMessageOffset = 0;
			while (infoMessageOffset != infoMessage.Length)
			{
                infoMessageOffset += networkStream.Read(infoMessage, infoMessageOffset, infoMessage.Length - infoMessageOffset);
			}
            Array.Reverse(infoMessage);

            int width = BitConverter.ToInt32(infoMessage, 8);
			int height = BitConverter.ToInt32(infoMessage, 4);
			int fps = BitConverter.ToInt32(infoMessage, 0);
			GD.Print($"Connection with {width}x{height} at {fps} fps");

            var cameraViewInstance = (Window)cameraView.Instantiate();
            TextureRect rect = (TextureRect)cameraViewInstance.FindChildren("*", nameof(TextureRect))[0];
            GetTree().Root.CallDeferred("add_child", cameraViewInstance);
            cameraViewInstance.Size = new Vector2I(width, height);

            Image image = Image.Create(width, height, false, Image.Format.Rgb8);
            texture2D = ImageTexture.CreateFromImage(image);
            rect.Texture = texture2D;

			textureRid = RenderingServer.TextureGetRdTexture(texture2D.GetRid());

            /*GD.Print($"Creating Texture...");
			RDTextureFormat textureFormat = new()
			{
				Width = (uint)width,
				Height = (uint)height,
				Depth = 1,
				TextureType = RenderingDevice.TextureType.Type2D,
				Format = RenderingDevice.DataFormat.R8G8B8A8Srgb,
				UsageBits = RenderingDevice.TextureUsageBits.SamplingBit | RenderingDevice.TextureUsageBits.CanUpdateBit
			};
            textureRid = RenderingServer.GetRenderingDevice().TextureCreate(textureFormat, new RDTextureView());
            GD.Print($"Texture ID is {textureRid} and {(textureRid.IsValid ? "is valid" : "is not valid")}");*/

            frameStream = new FrameStream(width * height * 4);

            await FFMpegArguments
				.FromPipeInput(new StreamPipeSource(networkStream), options => options
					.WithArgument(new FFMpegCore.Arguments.CustomArgument(@"-flags low_delay")))
				.OutputToPipe(new StreamPipeSink(frameStream), options => options
					.WithArgument(new FFMpegCore.Arguments.CustomArgument($@"-flags low_delay -s {width}x{height} -vcodec rawvideo -pix_fmt rgba -f image2pipe")))
				.ProcessAsynchronously();

            cameraViewInstance.Visible = false;
			cameraViewInstance.QueueFree();
		}
	}

	public override void _Process(double delta)
	{
        if (frameStream != null && frameStream.IsNewFrameAvailable)
        {
			if (renderingDevice == null)
			{
				renderingDevice = RenderingServer.GetRenderingDevice();
				GD.Print(renderingDevice.TextureGetFormat(textureRid).Format);
			}
            using (var frameAccessor = frameStream.GetFrameAccessor())
			{
                renderingDevice.TextureUpdate(textureRid, 0, frameAccessor.frame);
            }
        }
	}
}
