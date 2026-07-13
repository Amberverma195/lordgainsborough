import OrderStatusClient from './OrderStatusClient';

type OrderStatusPageProps = {
  params: Promise<{ id: string }>;
};

export default async function OrderStatusPage({ params }: OrderStatusPageProps) {
  const { id } = await params;
  return <OrderStatusClient orderId={id} />;
}
