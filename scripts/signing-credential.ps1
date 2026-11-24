# Keeps the Android release signing password in Windows Credential Manager instead of a file or a
# command line (security review L-14). Dot-source it; nothing here prints the password.
$script:NuvoriSigningTarget = 'Nuvori Android release signing'

if (-not ('Nuvori.SigningCredential' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
namespace Nuvori {
    public static class SigningCredential {
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct CREDENTIAL {
            public int Flags; public int Type; public string TargetName; public string Comment;
            public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
            public int CredentialBlobSize; public IntPtr CredentialBlob; public int Persist;
            public int AttributeCount; public IntPtr Attributes; public string TargetAlias; public string UserName;
        }
        [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern bool CredWriteW(ref CREDENTIAL credential, int flags);
        [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern bool CredReadW(string target, int type, int flags, out IntPtr credential);
        [DllImport("advapi32.dll")]
        static extern void CredFree(IntPtr buffer);
        const int GENERIC = 1, PERSIST_LOCAL_MACHINE = 2;

        public static void Write(string target, string secret) {
            byte[] blob = Encoding.Unicode.GetBytes(secret);
            IntPtr buffer = Marshal.AllocHGlobal(blob.Length);
            try {
                Marshal.Copy(blob, 0, buffer, blob.Length);
                var credential = new CREDENTIAL { Type = GENERIC, TargetName = target, UserName = Environment.UserName,
                    CredentialBlobSize = blob.Length, CredentialBlob = buffer, Persist = PERSIST_LOCAL_MACHINE };
                if (!CredWriteW(ref credential, 0)) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
            } finally {
                for (int i = 0; i < blob.Length; i++) Marshal.WriteByte(buffer, i, 0);
                Marshal.FreeHGlobal(buffer);
                Array.Clear(blob, 0, blob.Length);
            }
        }

        public static string Read(string target) {
            IntPtr pointer;
            if (!CredReadW(target, GENERIC, 0, out pointer)) return null;
            try {
                var credential = (CREDENTIAL)Marshal.PtrToStructure(pointer, typeof(CREDENTIAL));
                return Marshal.PtrToStringUni(credential.CredentialBlob, credential.CredentialBlobSize / 2);
            } finally { CredFree(pointer); }
        }
    }
}
'@
}

function Set-NuvoriSigningPassword([string]$Password) {
    [Nuvori.SigningCredential]::Write($script:NuvoriSigningTarget, $Password)
}

function Get-NuvoriSigningPassword {
    [Nuvori.SigningCredential]::Read($script:NuvoriSigningTarget)
}
