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

	Image image;
    ImageTexture texture;

    [Export] private PackedScene cameraView;

	public override void _Ready()
	{
		_ = ReceiveVideoAsync();
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

            image = Image.Create(width, height, false, Image.Format.Rgb8);
            texture = ImageTexture.CreateFromImage(image);
            rect.Texture = texture;

            frameStream = new FrameStream(width * height * 3);

            await FFMpegArguments
				.FromPipeInput(new StreamPipeSource(networkStream), options => options
					.WithArgument(new FFMpegCore.Arguments.CustomArgument(@"-flags low_delay")))
				.OutputToPipe(new StreamPipeSink(frameStream), options => options
					.WithArgument(new FFMpegCore.Arguments.CustomArgument($@"-flags low_delay -s {width}x{height} -vcodec rawvideo -pix_fmt rgb24 -f image2pipe")))
				.ProcessAsynchronously();

            cameraViewInstance.Visible = false;
			cameraViewInstance.QueueFree();
		}
	}

	public override void _Process(double delta)
	{
		if (frameStream != null && frameStream.IsNewFrameAvailable)
		{
            using (var frameAccessor = frameStream.GetFrameAccessor())
			{
                image.SetData(texture.GetWidth(), texture.GetHeight(), false, Image.Format.Rgb8, frameAccessor.frame);
            }
            texture.Update(image);
        }
	}
}
