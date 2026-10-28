// Подключение к игровому серверу через kcp2k.
// Требуется: исходники kcp2k (https://github.com/MirrorNetworking/kcp2k, папка kcp2k/kcp2k)
// и Google.Protobuf + сгенерированный Game.cs (protoc --csharp_out=... game.proto).
using System;
using System.Collections.Generic;
using Google.Protobuf;
using kcp2k;
using UGame.Protocol; // классы из game.proto
using UnityEngine;

namespace UGame.Net
{
    public sealed class GameConnection : MonoBehaviour
    {
        // Должно совпадать с kcp.* в game-server.properties
        static KcpConfig Config() => new KcpConfig(
            DualMode: false,
            Mtu: 1200,
            NoDelay: true,
            Interval: 10,
            FastResend: 0,
            CongestionWindow: false,
            SendWindowSize: 32,
            ReceiveWindowSize: 128,
            Timeout: 10000);

        public event Action<JoinResult> JoinResultReceived;
        public event Action<MatchStart> MatchStarted;
        public event Action<MatchEnd> MatchEnded;
        public event Action<Snapshot> SnapshotReceived;
        public event Action Disconnected;

        KcpClient client;
        string pendingTicket;

        readonly ByteWriter writer = new ByteWriter(64);
        readonly List<InputCommand> history = new List<InputCommand>(InputPacket.MaxCommands);
        readonly Snapshot snapshot = new Snapshot(); // переиспользуем — без мусора каждый кадр
        ushort nextSeq;

        public bool Connected => client != null && client.connected;

        /// <summary>host/port/ticket — из GET /api/matchmaking/status, когда state == MATCHED.</summary>
        public void Connect(string host, int port, string ticket)
        {
            kcp2k.Log.Info = Debug.Log;
            kcp2k.Log.Warning = Debug.LogWarning;
            kcp2k.Log.Error = Debug.LogError;

            pendingTicket = ticket;
            client = new KcpClient(OnConnected, OnData, OnDisconnected, OnError, Config());
            client.Connect(host, (ushort)port);
        }

        public void Disconnect() => client?.Disconnect();

        void OnConnected()
        {
            // рукопожатие kcp2k пройдено — представляемся билетом
            var join = new JoinRequest { Ticket = pendingTicket, ClientVersion = Application.version };
            SendReliable(MessageType.C2S_Join, join);
        }

        void OnData(ArraySegment<byte> data, KcpChannel channel)
        {
            byte type = data.Array[data.Offset];
            var body = new ArraySegment<byte>(data.Array, data.Offset + 1, data.Count - 1);
            try
            {
                switch (type)
                {
                    case MessageType.S2C_JoinResult:
                        JoinResultReceived?.Invoke(JoinResult.Parser.ParseFrom(body.Array, body.Offset, body.Count));
                        break;
                    case MessageType.S2C_MatchStart:
                        MatchStarted?.Invoke(MatchStart.Parser.ParseFrom(body.Array, body.Offset, body.Count));
                        break;
                    case MessageType.S2C_MatchEnd:
                        MatchEnded?.Invoke(MatchEnd.Parser.ParseFrom(body.Array, body.Offset, body.Count));
                        break;
                    case MessageType.S2C_Snapshot:
                        SnapshotReceived?.Invoke(Snapshot.ReadBody(new ByteReader(body), snapshot));
                        break;
                }
            }
            catch (Exception e)
            {
                Debug.LogWarning($"Битое сообщение {type}: {e.Message}");
            }
        }

        void OnDisconnected() => Disconnected?.Invoke();

        void OnError(ErrorCode code, string message) => Debug.LogWarning($"KCP {code}: {message}");

        /// <summary>Вызывайте с частотой тика (например, из FixedUpdate при fixedDeltaTime = 1/30).</summary>
        public ushort SendInput(Vector2 move, float aimRadians, Buttons buttons)
        {
            var cmd = new InputCommand { Seq = nextSeq++, MoveX = move.x, MoveY = move.y, Aim = aimRadians, Buttons = buttons };
            history.Insert(0, cmd);
            if (history.Count > InputPacket.MaxCommands) history.RemoveAt(history.Count - 1);

            writer.Reset();
            InputPacket.Write(writer, history);
            client.Send(writer.Segment, KcpChannel.Unreliable);
            return cmd.Seq; // сохраните для client-side prediction / reconciliation
        }

        void SendReliable(byte type, IMessage message)
        {
            writer.Reset().U8(type).Bytes(message.ToByteArray());
            client.Send(writer.Segment, KcpChannel.Reliable);
        }

        // kcp2k ничего не делает сам — его нужно «тикать».
        // Incoming в начале кадра, Outgoing в конце — минимальная задержка.
        void Update() => client?.TickIncoming();
        void LateUpdate() => client?.TickOutgoing();

        void OnDestroy() => client?.Disconnect();
    }
}
