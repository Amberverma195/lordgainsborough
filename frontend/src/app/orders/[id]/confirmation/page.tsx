import OrderConfirmationClient from './OrderConfirmationClient';

type OrderConfirmationPageProps = {
  params: Promise<{ id: string }>;
};

export default async function OrderConfirmationPage({ params }: OrderConfirmationPageProps) {
  const { id } = await params;
  return <OrderConfirmationClient orderId={id} />;
}
