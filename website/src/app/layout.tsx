import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";
import { Toaster } from "@/components/ui/toaster";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "NativeMorph Engine v1.3.1 — Run Godot games natively on Android",
  description:
    "Download the NativeMorph Engine APK (v1.3.1 — the 'Unable to setup Godot Engine, aborting' launch bug is fixed) and the full fixed source code. Godot 4.7.2 + 3.6.2 runtimes, on-device optimizer, no emulation.",
  keywords: [
    "NativeMorph",
    "Godot",
    "Android",
    "APK",
    "game runner",
    "Godot 4.7.2",
    "Godot 3.6.2",
    "pck",
    "source code",
  ],
  openGraph: {
    title: "NativeMorph Engine v1.3.1",
    description:
      "Your Godot games, natively Android. Fixed APK + source code downloads.",
    type: "website",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body
        className={`${geistSans.variable} ${geistMono.variable} antialiased bg-background text-foreground`}
      >
        {children}
        <Toaster />
      </body>
    </html>
  );
}
