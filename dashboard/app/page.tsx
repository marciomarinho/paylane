import Stage from "@/components/Stage";
import { getLive } from "@/lib/live";

// Operational view — always fetch the current state fresh from the services.
export const dynamic = "force-dynamic";

export default async function Page() {
  const data = await getLive();
  return <Stage data={data} />;
}
