"use client";

import React, { useState, useEffect, useCallback, use } from "react";
import {
    Loader2,
    AlertCircle,
    ShieldCheck,
    FileText,
    File,
    Briefcase,
    MessageSquareWarning,
    Landmark,
} from "lucide-react";

// ---------------------------------------------------------------------------
// TYPES (mirror the Spring Boot DTOs exactly, including the capitalised keys)
// ---------------------------------------------------------------------------

interface DisputeFileDto {
    fileId: string;
    fileName: string;
    fileSizeBytes: number;
    downloadUrl: string;
}

interface DisputeNegotiationDto {
    JobTitle: string;
    MilestoneTitle: string;
    DisputeId: string;
    reason: string;
    files: DisputeFileDto[];
    clientProposedAmount: number;
    originalAmount: number;
}

// ---------------------------------------------------------------------------
// PAGE COMPONENT
// ---------------------------------------------------------------------------

export default function FreelancerDisputePage({
                                                  params,
                                              }: {
    params: Promise<{ token: string }>;
}) {
    // The folder is named [token], so the URL part after /dispute/ arrives as "token"
    const { token } = use(params);

    const [dispute, setDispute] = useState<DisputeNegotiationDto | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    const fetchDispute = useCallback(async () => {
        try {
            const response = await fetch(
                `${process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080"}/api/v1/disputes/invite/${token}`
            );

            if (!response.ok) {
                throw new Error("This dispute link is invalid or has expired.");
            }

            const data: DisputeNegotiationDto = await response.json();
            setDispute(data);
        } catch (err: any) {
            setError(err.message || "Something went wrong loading this dispute.");
        } finally {
            setIsLoading(false);
        }
    }, [token]);

    useEffect(() => {
        fetchDispute();
    }, [fetchDispute]);

    // ---------------------------------------------------------------------------
    // LOADING VIEW
    // ---------------------------------------------------------------------------

    if (isLoading) {
        return (
            <div className="min-h-screen bg-[#f4f6f1] dark:bg-neutral-950 flex items-center justify-center">
                <Loader2 size={32} className="animate-spin text-[#3FCD6B]" />
            </div>
        );
    }

    // ---------------------------------------------------------------------------
    // ERROR VIEW
    // ---------------------------------------------------------------------------

    if (error || !dispute) {
        return (
            <div className="min-h-screen bg-[#f4f6f1] dark:bg-neutral-950 flex items-center justify-center p-6">
                <div className="max-w-md w-full bg-red-50 dark:bg-red-500/10 border border-red-200 dark:border-red-500/20 rounded-2xl p-6 flex items-start gap-3 text-red-600 dark:text-red-400">
                    <AlertCircle size={20} className="shrink-0 mt-0.5" />
                    <p className="text-sm font-medium leading-relaxed">{error ?? "Dispute not found."}</p>
                </div>
            </div>
        );
    }

    // ---------------------------------------------------------------------------
    // MAIN VIEW
    // ---------------------------------------------------------------------------

    const formatAmount = (n: number) =>
        n.toLocaleString("en-GB", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

    const formatSize = (bytes: number) =>
        bytes < 1024 * 1024
            ? `${Math.max(1, Math.round(bytes / 1024))} KB`
            : `${(bytes / (1024 * 1024)).toFixed(1)} MB`;

    const freelancerReceives = dispute.originalAmount - dispute.clientProposedAmount;

    return (
        <div className="min-h-screen bg-[#f4f6f1] dark:bg-neutral-950 font-sans text-slate-900 dark:text-neutral-100 selection:bg-[#3FCD6B]/30 pb-24">
            {/* Header */}
            <div className="sticky top-0 z-40 bg-[#f4f6f1]/80 dark:bg-neutral-950/80 backdrop-blur-md border-b border-slate-200 dark:border-neutral-800">
                <div className="max-w-4xl mx-auto px-6 py-4 flex items-center justify-between">
                    <div className="flex items-center gap-2 text-sm font-bold text-slate-900 dark:text-white">
                        <ShieldCheck size={18} className="text-[#3FCD6B]" /> TrustBridge
                    </div>
                    <div className="flex items-center gap-2 text-xs font-semibold text-slate-400 dark:text-neutral-500 bg-white dark:bg-neutral-900 px-3 py-1.5 rounded-full border border-slate-200 dark:border-neutral-800">
                        <Briefcase size={14} /> {dispute.JobTitle}
                    </div>
                </div>
            </div>

            <div className="max-w-4xl mx-auto px-6 pt-10 w-full">
                {/* Hero */}
                <div className="mb-12">
                    <span className="inline-flex items-center gap-1.5 text-[11px] font-bold text-red-700 bg-red-100 dark:bg-red-500/10 dark:text-red-400 px-3 py-1.5 rounded-full uppercase tracking-wider mb-4">
                        <MessageSquareWarning size={12} /> Dispute Raised
                    </span>
                    <h1
                        className="text-4xl md:text-5xl font-black tracking-tight text-slate-900 dark:text-white mb-4 leading-tight"
                        style={{ fontFamily: "Syne, sans-serif" }}
                    >
                        The client has raised a dispute
                    </h1>
                    <p className="text-slate-500 dark:text-neutral-400 font-medium">
                        Milestone: <span className="font-bold text-slate-700 dark:text-neutral-200">{dispute.MilestoneTitle}</span>. The
                        funds stay securely in escrow until this is resolved.
                    </p>
                </div>

                <div className="space-y-6">
                    {/* Amounts */}
                    <div className="bg-white dark:bg-neutral-900 border border-slate-200 dark:border-neutral-800 rounded-3xl p-8">
                        <h2 className="flex items-center gap-2 text-xs font-bold uppercase tracking-wider text-slate-400 dark:text-neutral-500 mb-6">
                            <Landmark size={14} /> What the client is asking for
                        </h2>
                        <div className="grid grid-cols-1 sm:grid-cols-3 gap-6">
                            <div>
                                <p className="text-xs font-semibold text-slate-400 dark:text-neutral-500 mb-1">Original milestone amount</p>
                                <p className="text-2xl font-black text-slate-900 dark:text-white">{formatAmount(dispute.originalAmount)}</p>
                            </div>
                            <div>
                                <p className="text-xs font-semibold text-slate-400 dark:text-neutral-500 mb-1">Refund requested by client</p>
                                <p className="text-2xl font-black text-red-600 dark:text-red-400">- {formatAmount(dispute.clientProposedAmount)}</p>
                            </div>
                            <div>
                                <p className="text-xs font-semibold text-slate-400 dark:text-neutral-500 mb-1">You would receive</p>
                                <p className="text-2xl font-black text-[#0A3D1A] dark:text-[#3FCD6B]">{formatAmount(freelancerReceives)}</p>
                            </div>
                        </div>
                        <p className="text-sm text-slate-500 dark:text-neutral-400 mt-6 leading-relaxed">
                            If you accept, {formatAmount(freelancerReceives)} is released to you and {formatAmount(dispute.clientProposedAmount)} is refunded to the client.
                        </p>
                    </div>

                    {/* Reason */}
                    <div className="bg-white dark:bg-neutral-900 border border-slate-200 dark:border-neutral-800 rounded-3xl p-8">
                        <h2 className="flex items-center gap-2 text-xs font-bold uppercase tracking-wider text-slate-400 dark:text-neutral-500 mb-4">
                            <FileText size={14} /> Client&#39;s reason
                        </h2>
                        <p className="text-slate-700 dark:text-neutral-300 leading-relaxed whitespace-pre-wrap break-words">
                            {dispute.reason}
                        </p>
                    </div>

                    {/* Evidence files */}
                    <div className="bg-white dark:bg-neutral-900 border border-slate-200 dark:border-neutral-800 rounded-3xl p-8">
                        <h2 className="flex items-center gap-2 text-xs font-bold uppercase tracking-wider text-slate-400 dark:text-neutral-500 mb-4">
                            <File size={14} /> Evidence from the client
                        </h2>
                        {dispute.files.length === 0 ? (
                            <p className="text-sm text-slate-400 dark:text-neutral-500">No files were attached.</p>
                        ) : (
                            <ul className="space-y-2">
                                {dispute.files.map((file) => (
                                    <li
                                        key={file.fileId}
                                        className="flex items-center justify-between gap-4 bg-slate-50 dark:bg-neutral-950 border border-slate-200 dark:border-neutral-800 rounded-xl px-4 py-3"
                                    >
                                        <span className="flex items-center gap-2 text-sm font-medium text-slate-700 dark:text-neutral-300 truncate">
                                            <File size={16} className="shrink-0 text-slate-400" /> {file.fileName}
                                        </span>
                                        <span className="text-xs text-slate-400 dark:text-neutral-500 shrink-0">
                                            {formatSize(file.fileSizeBytes)}
                                        </span>
                                    </li>
                                ))}
                            </ul>
                        )}
                    </div>

                    {/* 2D: the accept / reject / counter-offer buttons go here */}
                </div>
            </div>
        </div>
    );
}
