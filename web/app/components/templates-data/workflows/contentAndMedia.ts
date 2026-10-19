import { Video } from "lucide-react";
import type { WorkflowTemplate } from "./types";

// Workflow templates: Content & media.
export const contentAndMediaTemplates: WorkflowTemplate[] = [
  {
    key: "content-youtube-pipeline-workflow",
    category: "Content & media",
    icon: Video,
    description:
      "Researches a topic and drafts a script, generates a video with Veo, holds it for your review, " +
      "then uploads privately to YouTube. Turn on a weekly Schedule after creating it to run this " +
      "automatically - see the Trigger strip once the workflow is Active. Set the recipient email on the last node.",
    triggerChannel: "WEBHOOK",
    steps: [
      {
        type: "agent",
        label: "Content researcher & scriptwriter",
      },
      {
        type: "tool",
        label: "Generate video with Veo",
        config: { toolName: "generate_video" },
      },
      {
        type: "approval",
        label: "Review before publishing",
        config: { message: "Review the generated video, thumbnail and script before it's uploaded to YouTube." },
      },
      {
        type: "tool",
        label: "Upload privately to YouTube",
        config: { toolName: "upload_video_to_youtube" },
      },
      {
        type: "output",
        label: "Notify me it's ready to review",
        config: { channel: "email", emailTo: "" },
      },
    ],
  },
];
