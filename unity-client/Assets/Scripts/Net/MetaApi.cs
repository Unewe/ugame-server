// HTTP-клиент мета-сервиса: регистрация, вход, профиль, матчмейкинг.
using System;
using System.Text;
using System.Threading.Tasks;
using UnityEngine;
using UnityEngine.Networking;

namespace UGame.Net
{
    [Serializable] public class RegisterRequest { public string username; public string email; public string password; }
    [Serializable] public class LoginRequest { public string username; public string password; }
    [Serializable] public class RefreshRequest { public string refreshToken; }

    [Serializable]
    public class TokenResponse
    {
        public string accessToken;
        public string refreshToken;
        public long expiresIn;
        public long playerId;
        public string username;
    }

    [Serializable]
    public class Profile
    {
        public long id;
        public string username;
        public int trophies;
        public int matchesPlayed;
        public int wins;
    }

    [Serializable]
    public class MatchmakingStatus
    {
        public string state;   // IDLE | QUEUED | MATCHED
        public string matchId;
        public string host;
        public int port;
        public string ticket;
        public int team;
    }

    [Serializable] public class ApiError { public string code; public string detail; }

    public sealed class ApiException : Exception
    {
        public readonly long Status;
        public readonly string Code;
        public ApiException(long status, string code, string message) : base(message) { Status = status; Code = code; }
    }

    public sealed class MetaApi
    {
        readonly string baseUrl;
        public TokenResponse Tokens { get; private set; }

        public MetaApi(string baseUrl) { this.baseUrl = baseUrl.TrimEnd('/'); }

        public async Task<TokenResponse> Register(string username, string email, string password) =>
            Tokens = await Send<TokenResponse>("POST", "/api/auth/register",
                new RegisterRequest { username = username, email = email, password = password }, auth: false);

        public async Task<TokenResponse> Login(string username, string password) =>
            Tokens = await Send<TokenResponse>("POST", "/api/auth/login",
                new LoginRequest { username = username, password = password }, auth: false);

        public async Task<TokenResponse> Refresh() =>
            Tokens = await Send<TokenResponse>("POST", "/api/auth/refresh",
                new RefreshRequest { refreshToken = Tokens.refreshToken }, auth: false);

        public Task<Profile> Me() => Send<Profile>("GET", "/api/me", null);
        public Task<MatchmakingStatus> Queue() => Send<MatchmakingStatus>("POST", "/api/matchmaking/queue", null);
        public Task<MatchmakingStatus> CancelQueue() => Send<MatchmakingStatus>("DELETE", "/api/matchmaking/queue", null);
        public Task<MatchmakingStatus> Status() => Send<MatchmakingStatus>("GET", "/api/matchmaking/status", null);

        async Task<T> Send<T>(string method, string path, object body, bool auth = true)
        {
            try
            {
                return await SendOnce<T>(method, path, body, auth);
            }
            catch (ApiException e) when (auth && e.Status == 401 && Tokens?.refreshToken != null)
            {
                await Refresh(); // access-токен истёк — обновляем и повторяем один раз
                return await SendOnce<T>(method, path, body, auth);
            }
        }

        async Task<T> SendOnce<T>(string method, string path, object body, bool auth)
        {
            using var req = new UnityWebRequest(baseUrl + path, method);
            req.downloadHandler = new DownloadHandlerBuffer();
            if (body != null)
            {
                req.uploadHandler = new UploadHandlerRaw(Encoding.UTF8.GetBytes(JsonUtility.ToJson(body)));
                req.SetRequestHeader("Content-Type", "application/json");
            }
            if (auth && Tokens != null)
                req.SetRequestHeader("Authorization", "Bearer " + Tokens.accessToken);

            var op = req.SendWebRequest();
            var tcs = new TaskCompletionSource<bool>();
            op.completed += _ => tcs.TrySetResult(true);
            await tcs.Task;

            string text = req.downloadHandler.text;
            if (req.result != UnityWebRequest.Result.Success)
            {
                var err = string.IsNullOrEmpty(text) ? null : JsonUtility.FromJson<ApiError>(text);
                throw new ApiException(req.responseCode, err?.code ?? "network_error", err?.detail ?? req.error);
            }
            return string.IsNullOrEmpty(text) ? default : JsonUtility.FromJson<T>(text);
        }
    }
}
